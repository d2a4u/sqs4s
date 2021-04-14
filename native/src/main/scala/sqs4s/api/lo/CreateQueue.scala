package sqs4s.api.lo

import cats.effect.Async
import cats.syntax.all._
import org.http4s.{Request, Uri}
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.lo.CreateQueue.defaults._

import scala.concurrent.duration._
import scala.xml.Elem

final case class CreateQueue[F[_]: Async](
  name: String,
  sqsEndpoint: Uri,
  delay: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod,
  visibilityTimeout: Int = VisibilityTimeout
) extends Action[F, CreateQueue.Result] {

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val attributes = List(
      "DelaySeconds" -> delay.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString,
      "VisibilityTimeout" -> visibilityTimeout.toString
    )

    val params = attributes.zipWithIndex.flatMap {
      case ((key, value), index) =>
        List(
          s"Attribute.${index + 1}.Name" -> key,
          s"Attribute.${index + 1}.Value" -> value
        )
    } ++ List(
      Some("Action" -> "CreateQueue"),
      Some("QueueName" -> name),
      version
    ).flatten

    SignedRequest.get[F](
      params,
      sqsEndpoint,
      config.credentials,
      config.region
    ).render(logger)
  }

  def parseResponse(response: Elem): F[CreateQueue.Result] = {
    val queue = (response \\ "QueueUrl").text
    queue
      .nonEmpty
      .guard[Option]
      .as(CreateQueue.Result(queue).pure[F])
      .getOrElse(
        Async[F].raiseError(UnexpectedResponseError("QueueUrl", response))
      )
  }
}

object CreateQueue {
  object defaults {
    val DelaySeconds = 0.seconds
    val MaxMessageSize = 262144
    val MessageRetentionPeriod = 4.days
    val VisibilityTimeout = 30
  }

  final case class Result(queueUrl: String) extends AnyVal
}
