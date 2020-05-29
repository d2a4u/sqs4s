package sqs4s.api.hi

import cats.MonadError
import cats.effect.{Clock, Concurrent, Timer}
import cats.implicits._
import fs2.Stream
import org.http4s.client.Client
import sqs4s.api.SqsSettings
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
    messages: Stream[F, T],
    id: T => F[String],
    attributes: Map[String, String] = Map.empty,
    delay: Option[Duration] = None,
    dedupId: Option[String] = None,
    groupId: Option[String] = None,
    groupWithin: FiniteDuration = 1.second,
    maxConcurrent: Int = 128
  ): Stream[F, SendMessageBatch.Result]
}

object SqsProducer {
  def instance[F[_]: Clock: Timer: Concurrent: Client, T: SqsSerializer](
    settings: SqsSettings
  ) =
    new SqsProducer[F, T] {
      override def produce(
        t: T,
        attributes: Map[String, String] = Map.empty,
        delay: Option[Duration] = None,
        dedupId: Option[String] = None,
        groupId: Option[String] = None
      ): F[SendMessage.Result] =
        SendMessage[F, T](t, attributes, delay, dedupId, groupId)
          .runWith(settings)

      override def batchProduce(
        messages: Stream[F, T],
        id: T => F[String],
        attributes: Map[String, String] = Map.empty,
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
              .map(t => SqsSerializer.apply[T].serialize(t).getBytes.length)
              .fold
            if (totalSize <= 256000) {
              chunk
                .traverse { t =>
                  id(t).map { i =>
                    SendMessageBatch
                      .Entry(i, t, attributes, delay, dedupId, groupId)
                  }
                }
                .flatMap(SendMessageBatch(_).runWith(settings))
            } else {
              MonadError[F, Throwable]
                .raiseError[SendMessageBatch.Result](MessageTooLarge)
            }
          }
    }
}
