package sqs4s.api
package hi

import cats.MonadError
import cats.effect.{Clock, Concurrent, Timer}
import cats.syntax.all._
import fs2.Stream
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.api.errors.MessageTooLarge
import sqs4s.api.lo.{SendMessage, SendMessageBatch}
import sqs4s.serialization.SqsSerializer

import scala.concurrent.duration._

trait SqsProducer[F[_], T] {
  def produce(
    t: T,
    attributes: Map[String, String] = Map.empty,
    delay: Option[Duration] = None,
    dedupId: Option[String] = None,
    groupId: Option[String] = None
  ): F[SendMessage.Result]

  def batchProduce(
    messages: Stream[F, SendMessageBatch.BatchEntry[T]],
    id: T => F[String],
    delay: Option[Duration] = None,
    dedupId: Option[String] = None,
    groupId: Option[String] = None,
    groupWithin: FiniteDuration = 1.second,
    maxConcurrent: Int = 128
  ): Stream[F, SendMessageBatch.Result]
}

object SqsProducer {
  def apply[T]: ApplyPartiallyApplied[T] =
    new ApplyPartiallyApplied(dummy = true)

  def default[T]: DefaultPartiallyApplied[T] =
    new DefaultPartiallyApplied(dummy = true)

  private[hi] final class ApplyPartiallyApplied[T] private[SqsProducer] (
    private val dummy: Boolean
  ) extends AnyVal {
    def apply[F[_]: Concurrent: Timer: Clock](
      client: Client[F],
      config: SqsConfig[F],
      logger: Logger[F]
    )(
      implicit serializer: SqsSerializer[T]
    ): SqsProducer[F, T] =
      new SqsProducer[F, T] {
        override def produce(
          t: T,
          attributes: Map[String, String] = Map.empty,
          delay: Option[Duration] = None,
          dedupId: Option[String] = None,
          groupId: Option[String] = None
        ): F[SendMessage.Result] =
          SendMessage[F, T](t, attributes, delay, dedupId, groupId)
            .runWith(client, config, logger)

        override def batchProduce(
          messages: Stream[F, SendMessageBatch.BatchEntry[T]],
          id: T => F[String],
          delay: Option[Duration] = None,
          dedupId: Option[String] = None,
          groupId: Option[String] = None,
          groupWithin: FiniteDuration = 1.second,
          maxConcurrent: Int = 128
        ): Stream[F, SendMessageBatch.Result] =
          messages
            .groupWithin(10, groupWithin)
            .mapAsync(maxConcurrent) { chunk =>
              val totalSize = chunk
                .map(t => serializer.serialize(t.message).getBytes.length)
                .fold
              if (totalSize <= 256000) {
                chunk
                  .traverse { entry =>
                    id(entry.message).map { i =>
                      SendMessageBatch
                        .Entry(
                          i,
                          entry.message,
                          entry.attributes,
                          delay,
                          dedupId,
                          groupId
                        )
                    }
                  }
                  .flatMap(SendMessageBatch(_).runWith(client, config, logger))
              } else {
                MonadError[F, Throwable]
                  .raiseError[SendMessageBatch.Result](MessageTooLarge)
              }
            }
      }
  }

  private[hi] final class DefaultPartiallyApplied[T] private[SqsProducer] (
    private val dummy: Boolean
  ) extends AnyVal {
    def apply[F[_]: Concurrent: Timer: Clock](
      client: Client[F],
      config: SqsConfig[F]
    )(
      implicit serializer: SqsSerializer[T]
    ): SqsProducer[F, T] = {
      new ApplyPartiallyApplied(dummy = true).apply(
        client,
        config,
        Slf4jLogger.getLogger[F]
      )
    }
  }
}
