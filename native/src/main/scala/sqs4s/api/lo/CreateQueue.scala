package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.api.SqsSettings
import sqs4s.api.lo.CreateQueue.defaults._

import scala.concurrent.duration._
import scala.xml.Elem

case class CreateQueue[F[_]: Sync: Clock](
  name: String,
  sqsEndpoint: Uri,
  delay: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod)
    extends Action[F, String] {

  def runWith(setting: SqsSettings)(implicit client: Client[F]): F[String] = {
    val attributes = List(
      "DelaySeconds" -> delay.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString
    )

    val queries = List(
      "Action" -> "CreateQueue",
      "QueueName" -> name,
      "Version" -> "2012-11-05"
    )

    val params =
      (attributes.zipWithIndex
        .flatMap {
          case ((key, value), index) =>
            List(
              s"Attribute.${index + 1}.Name" -> key,
              s"Attribute.${index + 1}.Value" -> value
            )
        } ++ queries).sortBy {
        case (key, _) => key
      }

    for {
      req <- SignedRequest.get[F](params, sqsEndpoint, setting.auth).render
      resp <- client
        .expectOr[Elem](req)(handleError)
        .map(xml => (xml \\ "QueueUrl").text)
    } yield resp
  }
}

object CreateQueue {
  object defaults {
    val DelaySeconds = 0.seconds
    val MaxMessageSize = 262144
    val MessageRetentionPeriod = 4.days
  }
}
