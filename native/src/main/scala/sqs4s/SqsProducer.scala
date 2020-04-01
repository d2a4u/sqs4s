package sqs4s.native

import cats.implicits._
import cats.effect.{Clock, Sync}
import org.http4s.client.Client
import sqs4s.api.SqsSettings
import sqs4s.api.lo.SendMessage
import sqs4s.native.serialization.SqsSerializer

import scala.concurrent.duration.Duration

trait SqsProducer[F[_], T] {
  def produce(t: T): F[Unit] = produce(
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
  ): F[Unit]
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
    ): F[Unit] =
      SendMessage[F, T](t, attributes, delay, dedupId, groupId)
        .runWith(settings)
        .as(())
  }
}
