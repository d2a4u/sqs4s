package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.SqsSetting
import sqs4s.api.errors.SqsError
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq
import sqs4s.serialization.MessageEncoder

import scala.concurrent.duration.Duration
import scala.xml.{Elem, XML}

case class SendMessage[F[_]: Sync: Clock, T](
  message: T,
  queueUrl: String,
  attributes: Map[String, String] = Map.empty,
  delay: Option[Duration] = None,
  dedupId: Option[String] = None,
  groupId: Option[String] = None
)(implicit encoder: MessageEncoder[F, T, String, String])
    extends Action[F, SendMessage.Result] {

  def runWith(
    setting: SqsSetting
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
      uri <- Sync[F].fromEither(Uri.fromString(queueUrl))
      uriWithQueries = params.foldLeft(uri) {
        case (u, (key, value)) =>
          u.withQueryParam(key, value)
      }
      get <- Request[F](method = Method.POST, uri = uriWithQueries)
        .withHostHeader(uriWithQueries)
        .withExpiresHeaderF[F]()
        .flatMap(_.withXAmzDateHeaderF[F])
      creq = CReq[F](get)
      authed <- creq.toAuthorizedRequest(
        setting.accessKey,
        setting.secretKey,
        setting.region,
        "sqs"
      )
      resp <- client
        .expectOr[Elem](authed) {
          case resp if !resp.status.isSuccess =>
            for {
              bytes <- resp.body.compile.toChunk
              xml <- Sync[F].delay(XML.loadString(new String(bytes.toArray)))
            } yield SqsError.fromXml(resp.status, xml)
        }
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
