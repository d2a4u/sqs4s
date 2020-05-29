package sqs4s.api.hi

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Clock, Concurrent, Timer}
import cats.implicits._
import fs2._
import org.http4s.client.Client
import sqs4s.api.errors.{DeleteMessageBatchErrors, RetriableServerError}
import sqs4s.api.lo.{DeleteMessage, DeleteMessageBatch, ReceiveMessage}
import sqs4s.api.{ConsumerSettings, SqsSettings}
import sqs4s.serialization.SqsDeserializer

import scala.concurrent.duration._
import scala.concurrent.TimeoutException

trait SqsConsumer[F[_], T] {
  def consume(process: T => F[Unit]): F[Unit]

  // not suitable for FIFO queue
  def consumeAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 1.second,
    batch: Int = 256
  )(process: T => F[Unit]): Stream[F, Unit]

  def dequeue(): Stream[F, T]

  // not suitable for FIFO queue
  def dequeueAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 1.second,
    batch: Int = 256
  ): Stream[F, T]

  def peek(number: Int): Stream[F, T]

  def read: F[Chunk[ReceiveMessage.Result[T]]]
}

object SqsConsumer {
  def instance[F[
    _
  ]: Concurrent: Parallel: Clock: Timer: Client, T: SqsDeserializer[F, *]](
    consumerSettings: ConsumerSettings
  ): SqsConsumer[F, T] =
    new SqsConsumer[F, T] {
      private val settings =
        SqsSettings(consumerSettings.queue, consumerSettings.auth)

      override def consume(process: T => F[Unit]): F[Unit] = {
        def ack: Pipe[F, Chunk[ReceiveMessage.Result[T]], Unit] =
          input => {
            val toAck = input.evalMap { chunk =>
              chunk
                .parTraverse { entry =>
                  process(entry.body).as(DeleteMessage[F](entry.receiptHandle))
                }
            }
            toAck.flatMap { chunk =>
              Stream.chunk[F, DeleteMessage[F]](chunk).flatMap { del =>
                retry(del.runWith(settings).void)
              }
            }
          }

        Stream
          .repeatEval(read)
          .metered(consumerSettings.pollingRate)
          .filter(_.nonEmpty)
          .broadcastThrough(ack)
          .compile
          .drain
      }

      override def consumeAsync(
        maxConcurrent: Int,
        groupWithin: FiniteDuration = 1.second,
        batch: Int = 256
      )(process: T => F[Unit]): Stream[F, Unit] = {
        import DeleteMessageBatch._

        val ack: Pipe[F, Chunk[ReceiveMessage.Result[T]], Unit] =
          _.mapAsync(maxConcurrent) { chunk => // chunk of up to 10 elems
            chunk
              .parTraverse { entry =>
                process(entry.body)
                  .as(Entry(entry.messageId, entry.receiptHandle))
              }
              .flatMap { entries =>
                retry(DeleteMessageBatch[F](entries).runWith(settings))
                  .evalMap { result =>
                    result.errors match {
                      case head :: tail =>
                        DeleteMessageBatchErrors(
                          NonEmptyList.of(head, tail: _*)
                        ).raiseError[F, Unit]
                      case Nil => ().pure[F]
                    }
                  }
                  .compile
                  .lastOrError
              }
          }

        Stream
          .constant[F, ReceiveMessage[F, T]](
            ReceiveMessage[F, T](
              consumerSettings.maxRead,
              consumerSettings.visibilityTimeout,
              consumerSettings.waitTimeSeconds
            ),
            batch
          )
          .metered(consumerSettings.pollingRate)
          .mapAsync(maxConcurrent)(_.runWith(settings))
          .filter(_.nonEmpty)
          .broadcastThrough(ack)
      }

      override def dequeue(): Stream[F, T] = {
        val delete: Pipe[F, Chunk[ReceiveMessage.Result[T]], T] =
          _.flatMap { res =>
            Stream.chunk(res).flatMap { result =>
              val r = DeleteMessage[F](result.receiptHandle)
                .runWith(settings)
                .as(result.body)
              Stream.eval(r)
            }
          }
        Stream
          .repeatEval(read)
          .metered(consumerSettings.pollingRate)
          .filter(_.nonEmpty)
          .broadcastThrough(delete)
      }

      override def dequeueAsync(
        maxConcurrent: Int,
        groupWithin: FiniteDuration = 1.second,
        batch: Int = 256
      ): Stream[F, T] = {
        val delete: Pipe[F, Chunk[ReceiveMessage.Result[T]], Chunk[T]] =
          _.mapAsync(maxConcurrent) { chunk =>
            val records = chunk.map(res => (res.messageId, res.body)).toList
            val entries = chunk.map { result =>
              DeleteMessageBatch.Entry(result.messageId, result.receiptHandle)
            }
            DeleteMessageBatch(entries).runWith(settings).map { deleted =>
              val ids = deleted.successes.map(_.id)
              Chunk.seq(records.collect {
                case (k, v) if ids.contains(k) => v
              })
            }
          }

        Stream
          .constant[F, ReceiveMessage[F, T]](
            ReceiveMessage[F, T](
              consumerSettings.maxRead,
              consumerSettings.visibilityTimeout,
              consumerSettings.waitTimeSeconds
            ),
            batch
          )
          .metered(consumerSettings.pollingRate)
          .mapAsync(maxConcurrent)(_.runWith(settings))
          .filter(_.nonEmpty)
          .broadcastThrough(delete)
          .flatMap(Stream.chunk)
      }

      override def peek(number: Int): Stream[F, T] = {
        Stream
          .eval(read)
          .repeatN(((number / consumerSettings.maxRead) + 1).toLong)
          .metered(consumerSettings.pollingRate)
          .flatMap(l => Stream.chunk(l.map(_.body)))
      }

      override def read: F[Chunk[ReceiveMessage.Result[T]]] =
        ReceiveMessage[F, T](
          consumerSettings.maxRead,
          consumerSettings.visibilityTimeout,
          consumerSettings.waitTimeSeconds
        ).runWith(settings)

      private def retry[U](f: F[U]) =
        Stream
          .retry[F, U](
            f,
            consumerSettings.initialDelay,
            _ * 2,
            consumerSettings.maxRetry,
            {
              case _: TimeoutException => true
              case _: RetriableServerError => true
            }
          )
    }
}
