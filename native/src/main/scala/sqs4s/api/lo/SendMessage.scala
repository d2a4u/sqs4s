package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.native.serialization.SqsSerializer
import sqs4s.api.SqsSettings

import scala.concurrent.duration.Duration
import scala.xml.Elem

case class SendMessage[F[_]: Sync: Clock, T](
  message: T,
  attributes: Map[String, String] = Map.empty,
  delay: Option[Duration] = None,
  dedupId: Option[String] = None,
  groupId: Option[String] = None
)(implicit encoder: SqsSerializer[F, T])
    extends Action[F, SendMessage.Result] {

  def runWith(
    setting: SqsSettings
  )(implicit client: Client[F]
  ): F[SendMessage.Result] = {
    val paramsF = encoder.encode(message).map { msg =>
      val queries = List(
        "Action" -> "SendMessage",
        "MessageBody" -> msg,
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
              s"Attribute.${index + 1}.Name" -> key,
              s"Attribute.${index + 1}.Value" -> value
            )
        } ++ queries).sortBy {
        case (key, _) => key
      }
    }

    for {
      params <- paramsF
      req <- SignedRequest.post(params, setting.queue, setting.auth).render
      resp <- client
        .expectOr[Elem](req)(handleError)
        .map { xml =>
          val md5MsgBody = (xml \\ "MD5OfMessageBody").text
          val md5MsgAttr = (xml \\ "MD5OfMessageAttributes").text
          val mid = (xml \\ "MessageId").text
          val rid = (xml \\ "RequestId").text
          SendMessage.Result(md5MsgBody, md5MsgAttr, mid, rid)
        }
    } yield resp
  }
}

object SendMessage {
  case class Result(
    messageBodyMd5: String,
    messageAttributesMd5: String,
    messageId: String,
    requestId: String)
}
