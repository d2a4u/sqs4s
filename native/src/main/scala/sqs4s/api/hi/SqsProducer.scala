package sqs4s.api.hi

import cats.effect.{Clock, Sync}
import cats.implicits._
import fs2.Chunk
import org.http4s.client.Client
import sqs4s.api.SqsSettings
import sqs4s.api.lo.{SendMessage, SendMessageBatch}
import sqs4s.serialization.SqsSerializer

import scala.concurrent.duration.Duration

trait SqsProducer[F[_], T] {
  def produce(t: T): F[SendMessage.Result] = produce(
    t = t,
    attributes = Map.empty,
    delay = None,
    dedupId = None,
    groupId = None
  )

  def produce(
    t: T,
    attributes: Map[String, String],
    delay: Option[Duration],
    dedupId: Option[String],
    groupId: Option[String]
  ): F[SendMessage.Result]

  def batchProduce(
    chunk: Chunk[SendMessageBatch.Entry[T]]
  ): F[SendMessageBatch.Result]

  def batchProduce(
    chunk: Chunk[T],
    id: T => F[String],
    attributes: Map[String, String],
    delay: Option[Duration],
    dedupId: Option[String],
    groupId: Option[String]
  ): F[SendMessageBatch.Result]

  def batchProduce(
    chunk: Chunk[T],
    id: T => F[String]
  ): F[SendMessageBatch.Result] = batchProduce(
    chunk,
    id,
    attributes = Map.empty,
    delay = None,
    dedupId = None,
    groupId = None
  )
}

object SqsProducer {
  def instance[F[_]: Sync: Clock: Client, T: SqsSerializer](
    settings: SqsSettings
  ) = new SqsProducer[F, T] {
    override def produce(
      t: T,
      attributes: Map[String, String],
      delay: Option[Duration],
      dedupId: Option[String],
      groupId: Option[String]
    ): F[SendMessage.Result] =
      SendMessage[F, T](t, attributes, delay, dedupId, groupId)
        .runWith(settings)

    override def batchProduce(
      chunk: Chunk[SendMessageBatch.Entry[T]]
    ): F[SendMessageBatch.Result] =
      SendMessageBatch(chunk).runWith(settings)

    override def batchProduce(
      chunk: Chunk[T],
      id: T => F[String],
      attributes: Map[String, String],
      delay: Option[Duration],
      dedupId: Option[String],
      groupId: Option[String]
    ): F[SendMessageBatch.Result] = {
      val entriesF = chunk.traverse { t =>
        id(t).map { i =>
          SendMessageBatch.Entry(i, t, attributes, delay, dedupId, groupId)
        }
      }
      entriesF.flatMap(SendMessageBatch(_).runWith(settings))
    }
  }
}
