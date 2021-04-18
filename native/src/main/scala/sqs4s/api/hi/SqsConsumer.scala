package sqs4s.api
package hi

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Clock, Concurrent}
import cats.syntax.all._
import fs2._
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.api.errors.{DeleteMessageBatchErrors, RetriableServerError}
import sqs4s.api.lo._
import sqs4s.serialization.SqsDeserializer

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import cats.effect.Temporal

trait SqsConsumer[F[_], T] {

  /** Read messages from SQS queue as T, for each message, apply process
    * `process` function and automatically acknowledge the message on completion
    * of `process`.
    *
    * Suitable for FIFO and non-FIFO queue. The message is acknowledged one by
    * one instead of batching.
    */
  def consume(process: T => F[Unit]): F[Unit]

  /** Parallel read messages from SQS queue as a Stream of T, for each message,
    * apply process `process` function and automatically acknowledge the
    * messages in batch.
    *
    * NOTE: This is not suitable for FIFO queue because it reads messages in
    * parallel so the order is lost.
    */
  def consumeAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 500.millis
  )(
    process: T => F[Unit]
  ): Stream[F, Unit]

  /** Read messages from SQS queue as a Stream of T, automatically acknowledge
    * messages BEFORE pushing T into the Stream.
    *
    * Suitable for FIFO and non-FIFO queue. The message is acknowledged one by
    * one instead of batching.
    */
  def dequeue: Stream[F, T]

  /** Parallel read messages from SQS queue as a Stream of T, automatically
    * acknowledge messages BEFORE pushing T into the Stream.
    *
    * NOTE: This is not suitable for FIFO queue because it reads messages in
    * parallel so the order is lost.
    */
  def dequeueAsync(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 500.millis
  ): Stream[F, T]

  /** Read N messages from the queue without acknowledge them.
    */
  def peek(number: Int): Stream[F, T]

  /** Similar to `peek` but return also a ReceiptHandle to manually acknowledge
    * messages and return up to `maxRead` number of messages.
    */
  def read: F[Chunk[ReceiveMessage.Result[T]]]

  /** Similar to `read` but return a Stream of `ReceiveMessage.Result[T]` which
    * can be used to manually acknowledge messages.
    */
  def reads: Stream[F, ReceiveMessage.Result[T]]

  /** Similar to `reads` but read messages in parallel.
    *
    * NOTE: This is not suitable for FIFO queue because it reads messages in
    * parallel so the order is lost.
    */
  def readsAsync(maxConcurrent: Int): Stream[F, ReceiveMessage.Result[T]]

  /** Acknowledge that a message has been read.
    */
  def ack: Pipe[F, ReceiptHandle, Unit]

  /** Batch acknowledge that messages has been read.
    */
  def batchAck(
    maxConcurrent: Int,
    groupWithin: FiniteDuration = 500.millis
  ): Pipe[F, DeleteMessageBatch.Entry, Unit]
}

object SqsConsumer {

  private val MaxBatchSize = 10

  def apply[T]: ApplyPartiallyApplied[T] =
    new ApplyPartiallyApplied(dummy = true)

  def default[T]: DefaultPartiallyApplied[T] =
    new DefaultPartiallyApplied(dummy = true)

  private[hi] final class ApplyPartiallyApplied[T] private[SqsConsumer] (
    private val dummy: Boolean
  ) extends AnyVal {
    def apply[F[_]: Concurrent: Parallel: Clock: Temporal: SqsDeserializer[
      *[_],
      T
    ]](
      client: Client[F],
      consumerConfig: ConsumerConfig[F],
      logger: Logger[F]
    ): SqsConsumer[F, T] =
      new SqsConsumer[F, T] {
        private val config =
          SqsConfig(
            consumerConfig.queue,
            consumerConfig.credentials,
            consumerConfig.region
          )

        override def consume(process: T => F[Unit]): F[Unit] = {
          def ack: Pipe[F, ReceiveMessage.Result[T], Unit] =
            _.flatMap { entry =>
              Stream.eval(process(entry.body)).flatMap { _ =>
                retry(DeleteMessage[F](entry.receiptHandle).runWith(
                  client,
                  config,
                  logger
                ).void)
              }
            }

          reads
            .through(ack)
            .compile
            .drain
        }

        override def consumeAsync(
          maxConcurrent: Int,
          groupWithin: FiniteDuration = 500.millis
        )(process: T => F[Unit]): Stream[F, Unit] = {
          import DeleteMessageBatch._

          val ack: Pipe[F, Chunk[ReceiveMessage.Result[T]], Unit] =
            _.flatMap { chunk => // chunk of up to 10 elems
              val processed = chunk
                .parTraverse { entry =>
                  process(entry.body)
                    .as(Entry(entry.messageId, entry.receiptHandle))
                }
              Stream.evalUnChunk(processed).groupWithin(
                MaxBatchSize,
                groupWithin
              ).map {
                entries =>
                  val unique =
                    dedup[DeleteMessageBatch.Entry, String](entries, _.id)
                  retry(DeleteMessageBatch[F](unique).runWith(
                    client,
                    config,
                    logger
                  ))
                    .evalMap { result =>
                      result.errors match {
                        case head :: tail =>
                          DeleteMessageBatchErrors(
                            NonEmptyList.of(head, tail: _*)
                          ).raiseError[F, Unit]
                        case Nil => ().pure[F]
                      }
                    }
              }.parJoin(maxConcurrent)
            }

          readsAsync(maxConcurrent)
            .groupWithin(MaxBatchSize, groupWithin)
            .through(ack)
        }

        override def dequeue: Stream[F, T] = {
          val delete: Pipe[F, ReceiveMessage.Result[T], T] =
            _.flatMap { entry =>
              retry(DeleteMessage[F](entry.receiptHandle).runWith(
                client,
                config,
                logger
              ).as(entry.body))
            }
          reads
            .through(delete)
        }

        override def dequeueAsync(
          maxConcurrent: Int,
          groupWithin: FiniteDuration = 500.millis
        ): Stream[F, T] = {
          val delete: Pipe[F, Chunk[ReceiveMessage.Result[T]], Chunk[T]] =
            _.mapAsync(maxConcurrent) { chunk =>
              val records = chunk.map(res => (res.messageId, res.body)).toList
              val entries = chunk.map { result =>
                DeleteMessageBatch.Entry(result.messageId, result.receiptHandle)
              }
              val unique =
                dedup[DeleteMessageBatch.Entry, String](entries, _.id)
              DeleteMessageBatch(unique).runWith(client, config, logger).map {
                deleted =>
                  val ids = deleted.successes.map(_.id)
                  Chunk.seq(records.collect {
                    case (k, v) if ids.contains(k) => v
                  })
              }
            }

          readsAsync(maxConcurrent)
            .groupWithin(MaxBatchSize, groupWithin)
            .through(delete)
            .flatMap(Stream.chunk)
        }

        override def peek(number: Int): Stream[F, T] = {
          Stream
            .eval(read)
            .repeatN(((number / consumerConfig.maxRead) + 1).toLong)
            .metered(consumerConfig.pollingRate)
            .flatMap(l => Stream.chunk(l.map(_.body)))
        }

        override def read: F[Chunk[ReceiveMessage.Result[T]]] =
          ReceiveMessage[F, T](
            consumerConfig.maxRead,
            consumerConfig.visibilityTimeout,
            consumerConfig.waitTimeSeconds
          ).runWith(client, config, logger)

        override def reads: Stream[F, ReceiveMessage.Result[T]] =
          Stream
            .constant[F, ReceiveMessage[F, T]](
              ReceiveMessage[F, T](
                consumerConfig.maxRead,
                consumerConfig.visibilityTimeout,
                consumerConfig.waitTimeSeconds
              )
            )
            .metered(consumerConfig.pollingRate)
            .evalMap(_.runWith(client, config, logger))
            .filter(_.nonEmpty)
            .flatMap(Stream.chunk)

        override def readsAsync(maxConcurrent: Int)
          : Stream[F, ReceiveMessage.Result[T]] =
          Stream
            .constant[F, ReceiveMessage[F, T]](
              ReceiveMessage[F, T](
                consumerConfig.maxRead,
                consumerConfig.visibilityTimeout,
                consumerConfig.waitTimeSeconds
              )
            )
            .metered(consumerConfig.pollingRate)
            .mapAsync(maxConcurrent)(_.runWith(client, config, logger))
            .filter(_.nonEmpty)
            .flatMap(Stream.chunk)

        override def ack: Pipe[F, ReceiptHandle, Unit] =
          _.evalMap { handle =>
            DeleteMessage[F](handle).runWith(client, config, logger).void
          }

        override def batchAck(
          maxConcurrent: Int,
          groupWithin: FiniteDuration = 500.millis
        ): Pipe[F, DeleteMessageBatch.Entry, Unit] =
          _.groupWithin(MaxBatchSize, groupWithin).map { entries =>
            val unique = dedup[DeleteMessageBatch.Entry, String](entries, _.id)
            retry(DeleteMessageBatch[F](unique).runWith(
              client,
              config,
              logger
            ))
              .evalMap { result =>
                result.errors match {
                  case head :: tail =>
                    DeleteMessageBatchErrors(
                      NonEmptyList.of(head, tail: _*)
                    ).raiseError[F, Unit]
                  case Nil => ().pure[F]
                }
              }
          }.parJoin(maxConcurrent)

        private def retry[U](f: F[U]): Stream[F, U] =
          Stream
            .retry[F, U](
              f,
              consumerConfig.initialDelay,
              _ * 2,
              consumerConfig.maxRetry,
              {
                case _: TimeoutException => true
                case _: RetriableServerError => true
                case _ => false
              }
            )

        private def dedup[V, U](entries: Chunk[V], id: V => U): Seq[V] =
          entries.toList.map(t => id(t) -> t).toMap.values.toSeq
      }
  }

  private[hi] final class DefaultPartiallyApplied[T] private[SqsConsumer] (
    private val dummy: Boolean
  ) extends AnyVal {
    def apply[F[_]: Concurrent: Parallel: Clock: Temporal: SqsDeserializer[
      *[_],
      T
    ]](
      client: Client[F],
      consumerConfig: ConsumerConfig[F]
    ): SqsConsumer[F, T] = {
      new ApplyPartiallyApplied(dummy = true).apply(
        client,
        consumerConfig,
        Slf4jLogger.getLogger[F]
      )
    }
  }
}
