package sqs4s.api.hi

import cats.effect.{Clock, Concurrent, Sync, Timer}
import cats.implicits._
import fs2._
import org.http4s.client.Client
import sqs4s.api.errors.{RetriableServerError, UnknownDeleteMessageBatchError}
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
  )(
    process: T => F[Unit],
    handleErrorWith: T => DeleteMessageBatch.Error => F[Unit]
  ): F[Unit]

  def dequeue(): Stream[F, T]

  // not suitable for FIFO queue
  def dequeueAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 1.second,
    batch: Int = 256
  ): Stream[F, T]

  def peek(number: Int): Stream[F, T]

  def read: F[List[ReceiveMessage.Result[T]]]
}

object SqsConsumer {
  def instance[
    F[_]: Concurrent: Clock: Timer: Client,
    T: SqsDeserializer[F, ?]
  ](
    settings: SqsSettings,
    consumerSettings: ConsumerSettings = ConsumerSettings.default
  ): SqsConsumer[F, T] =
    new DefaultSqsConsumer[F, T](settings, consumerSettings) {}
}

abstract class DefaultSqsConsumer[
  F[_]: Concurrent: Clock: Timer: Client,
  T: SqsDeserializer[F, ?]
](
  settings: SqsSettings,
  consumerSettings: ConsumerSettings = ConsumerSettings.default)
    extends SqsConsumer[F, T] {

  override def consume(process: T => F[Unit]): F[Unit] = {
    def ack(proc: T => F[Unit]): Pipe[F, ReceiveMessage.Result[T], Unit] =
      _.flatMap { res =>
        Stream.eval(proc(res.body)).flatMap { _ =>
          val deletion = DeleteMessage[F](res.receiptHandle)
            .runWith(settings)
            .void
          retry(deletion)
        }
      }

    Stream
      .repeatEval(read)
      .flatMap(Stream.emits)
      .broadcastThrough(ack(process))
      .compile
      .drain
  }

  override def consumeAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 1.second,
    batch: Int = 256
  )(
    process: T => F[Unit],
    handleErrorWith: T => DeleteMessageBatch.Error => F[Unit]
  ): F[Unit] = {

    def ack(
      proc: T => F[Unit]
    ): Pipe[F, Chunk[ReceiveMessage.Result[T]], Unit] = {
      _.evalMap { chunk =>
        val records = chunk.map(res => (res.messageId, res.body)).toList.toMap
        val entriesF = chunk.traverse { result =>
          proc(result.body).as {
            DeleteMessageBatch.Entry(result.messageId, result.receiptHandle)
          }
        }
        for {
          entries <- entriesF
          batch <- retry(DeleteMessageBatch(entries).runWith(settings)).compile.lastOrError
          void <- if (batch.errors.isEmpty) {
            ().pure[F]
          } else {
            batch.errors.traverse { error =>
              Sync[F]
                .fromOption(
                  records.get(error.id),
                  UnknownDeleteMessageBatchError(error)
                )
                .flatMap(t => handleErrorWith(t)(error))
            }.void
          }
        } yield void
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
      .mapAsync(maxConcurrent)(_.runWith(settings))
      .flatMap(Stream.emits)
      .groupWithin(10, groupWithin)
      .broadcastThrough(ack(process))
      .compile
      .drain
  }

  override def dequeue(): Stream[F, T] = {
    val delete: Pipe[F, ReceiveMessage.Result[T], T] =
      _.flatMap { res =>
        val r = DeleteMessage[F](res.receiptHandle)
          .runWith(settings)
          .as(res.body)
        Stream.eval(r)
      }
    Stream.repeatEval(read).flatMap(Stream.emits).broadcastThrough(delete)
  }

  override def dequeueAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 1.second,
    batch: Int = 256
  ): Stream[F, T] = {
    val delete: Pipe[F, Chunk[ReceiveMessage.Result[T]], Chunk[T]] =
      _.evalMap { chunk =>
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
      .mapAsync(maxConcurrent)(_.runWith(settings))
      .flatMap(Stream.emits)
      .groupWithin(10, groupWithin)
      .broadcastThrough(delete)
      .flatMap(Stream.chunk)
  }

  override def peek(number: Int): Stream[F, T] = {
    Stream
      .eval(read)
      .repeatN(((number / consumerSettings.maxRead) + 1).toLong)
      .flatMap(l => Stream.emits[F, T](l.map(_.body)))
  }

  override def read: F[List[ReceiveMessage.Result[T]]] =
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
        consumerSettings.maxRetry, {
          case _: TimeoutException => true
          case RetriableServerError => true
        }
      )
}
