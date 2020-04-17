package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Request, Uri}
import sqs4s.api.SqsSettings
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.lo.CreateQueue.defaults._

import scala.concurrent.duration._
import scala.xml.Elem

case class CreateQueue[F[_]: Sync: Clock](
  name: String,
  sqsEndpoint: Uri,
  delay: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod,
  visibilityTimeout: Int = VisibilityTimeout)
    extends Action[F, CreateQueue.Result] {

  def mkRequest(setting: SqsSettings): F[Request[F]] = {
    val attributes = List(
      "DelaySeconds" -> delay.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString,
      "VisibilityTimeout" -> visibilityTimeout.toString
    )

    val queries = List(
      "Action" -> "CreateQueue",
      "QueueName" -> name,
      "Version" -> "2012-11-05"
    )

    val params =
      attributes.zipWithIndex
        .flatMap {
          case ((key, value), index) =>
            List(
              s"Attribute.${index + 1}.Name" -> key,
              s"Attribute.${index + 1}.Value" -> value
            )
        } ++ queries

    SignedRequest.get[F](params, sqsEndpoint, setting.auth).render
  }

  def parseResponse(response: Elem): F[CreateQueue.Result] = {
    val queue = (response \\ "QueueUrl").text
    queue.nonEmpty
      .guard[Option]
      .as {
        CreateQueue.Result(queue).pure[F]
      }
      .getOrElse {
        Sync[F].raiseError(UnexpectedResponseError("QueueUrl", response))
      }
  }
}

object CreateQueue {
  object defaults {
    val DelaySeconds = 0.seconds
    val MaxMessageSize = 262144
    val MessageRetentionPeriod = 4.days
    val VisibilityTimeout = 30
  }

  case class Result(queueUrl: String) extends AnyVal
}
