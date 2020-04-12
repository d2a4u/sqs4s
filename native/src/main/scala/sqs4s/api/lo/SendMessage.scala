package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Request
import sqs4s.serialization.SqsSerializer
import sqs4s.api.SqsSettings
import sqs4s.api.errors.UnexpectedResponseError

import scala.concurrent.duration.Duration
import scala.xml.Elem

case class SendMessage[F[_]: Sync: Clock, T](
  message: T,
  attributes: Map[String, String] = Map.empty,
  delay: Option[Duration] = None,
  dedupId: Option[String] = None,
  groupId: Option[String] = None
)(implicit serializer: SqsSerializer[T])
    extends Action[F, SendMessage.Result] {

  def mkRequest(settings: SqsSettings): F[Request[F]] = {
    val params = {
      val queries = List(
        "Action" -> "SendMessage",
        "MessageBody" -> serializer.serialize(message),
        "Version" -> "2012-11-05"
      ) ++ (
        dedupId.map(ddid => List("MessageDeduplicationId" -> ddid)) |+|
          groupId.map(gid => List("MessageGroupId" -> gid)) |+|
          delay.map(d => List("DelaySeconds" -> d.toSeconds.toString))
      ).getOrElse(List.empty)

      (attributes.zipWithIndex.toList
        .flatMap {
          case ((key, value), index) =>
            List(
              s"MessageAttribute.${index + 1}.Name" -> key,
              s"MessageAttribute.${index + 1}.Value" -> value
            )
        } ++ queries).sortBy {
        case (key, _) => key
      }
    }

    SignedRequest.post(params, settings.queue, settings.auth).render
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
      SendMessage
        .Result(
          md5MsgBody,
          md5MsgAttr.nonEmpty.guard[Option].as(md5MsgAttr.text),
          mid,
          rid
        )
        .pure[F]
    }).getOrElse(
      Sync[F].raiseError(
        UnexpectedResponseError(
          "MD5OfMessageBody, MD5OfMessageAttributes, MessageId, RequestId",
          response
        )
      )
    )
  }
}

object SendMessage {
  case class Result(
    messageBodyMd5: String,
    messageAttributesMd5: Option[String],
    messageId: String,
    requestId: String)
}
