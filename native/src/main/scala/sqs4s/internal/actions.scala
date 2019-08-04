package sqs4s.internal

import cats.effect.Sync
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.internal.CreateQueue.defaults._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.Elem

case class SqsSetting(url: String)

trait Action

case class CreateQueue(
    name: String,
    delaySeconds: Duration = DelaySeconds,
    maxMessageSize: Int = MaxMessageSize,
    messageRetentionPeriod: Duration = MessageRetentionPeriod)
    extends Action {

  def run[F[_]: Sync](
      implicit setting: SqsSetting,
      client: Client[F]
    ): F[String] = {
    val attributes = List(
      "DelaySeconds" -> delaySeconds.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString
    ).sortBy {
      case (key, _) => key
    }

    val queries = List(
      "Action" -> "CreateQueue",
      "QueueName" -> name,
      "Version" -> "2012-11-05"
    )

    val queryQueries =
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

    Sync[F].fromEither(Uri.fromString(setting.url)).flatMap { parsed =>
      val uri = queryQueries.foldLeft(parsed) {
        case (u, (key, value)) =>
          u.withQueryParam(key, value)
      }

      client.expect[Elem](uri).map(xml => (xml \\ "QueueUrl").text)
    }
  }
}

object CreateQueue {
  object defaults {
    val DelaySeconds = 0.seconds
    val MaxMessageSize = 262144
    val MessageRetentionPeriod = 4.days
  }
}

case class SendMessage[T](message: T)
