package sqs4s.api.lo

import cats.effect.Async
import cats.syntax.all._
import org.http4s.Request
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.serialization.SqsSerializer

import scala.concurrent.duration.Duration
import scala.xml.Elem

final case class SendMessage[F[_]: Async, T](
  message: T,
  attributes: Map[String, String] = Map.empty,
  delay: Option[Duration] = None,
  dedupId: Option[String] = None,
  groupId: Option[String] = None
)(implicit serializer: SqsSerializer[T])
    extends Action[F, SendMessage.Result] {

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val params = attributes.zipWithIndex.toList.flatMap {
      case ((key, value), index) =>
        List(
          s"MessageAttribute.${index + 1}.Name" -> key,
          s"MessageAttribute.${index + 1}.Value" -> value
        )
    } ++ List(
      Some("Action" -> "SendMessage"),
      Some("MessageBody" -> serializer.serialize(message)),
      version,
      dedupId.map(ddid => "MessageDeduplicationId" -> ddid),
      groupId.map(gid => "MessageGroupId" -> gid),
      delay.map(d => "DelaySeconds" -> d.toSeconds.toString)
    ).flatten

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).render(logger)
  }

  def parseResponse(response: Elem): F[SendMessage.Result] = {
    val md5MsgBody = (response \\ "MD5OfMessageBody").text
    val md5MsgAttr = response \\ "MD5OfMessageAttributes"
    val mid = (response \\ "MessageId").text
    val rid = (response \\ "RequestId").text
    (for {
      _ <- md5MsgBody.nonEmpty.guard[Option]
      _ <- mid.nonEmpty.guard[Option]
      _ <- rid.nonEmpty.guard[Option]
    } yield {
      SendMessage.Result(
        md5MsgBody,
        md5MsgAttr.nonEmpty.guard[Option].as(md5MsgAttr.text),
        mid,
        rid
      ).pure[F]
    }).getOrElse(
      Async[F].raiseError(
        UnexpectedResponseError(
          "MD5OfMessageBody, MD5OfMessageAttributes, MessageId, RequestId",
          response
        )
      )
    )
  }
}

object SendMessage {
  final case class Result(
    messageBodyMd5: String,
    messageAttributesMd5: Option[String],
    messageId: String,
    requestId: String
  )
}
