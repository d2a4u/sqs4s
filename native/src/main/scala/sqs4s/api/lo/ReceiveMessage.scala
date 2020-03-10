package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.api.SqsSetting
import sqs4s.serialization.MessageDecoder

import scala.xml.Elem

case class ReceiveMessage[F[_]: Sync: Clock, T](
  queue: Uri,
  maxNumberOfMessages: Int = 1,
  visibilityTimeout: Int = 15,
  attributes: Map[String, String] = Map.empty,
  waitTimeSeconds: Option[Int] = None
)(implicit decoder: MessageDecoder[F, String, String, T])
    extends Action[F, Seq[ReceiveMessage.Result[T]]] {

  def runWith(
    setting: SqsSetting
  )(implicit client: Client[F]
  ): F[Seq[ReceiveMessage.Result[T]]] = {
    val queries = List(
      "Action" -> "ReceiveMessage",
      "MaxNumberOfMessages" -> maxNumberOfMessages.toString,
      "VisibilityTimeout" -> visibilityTimeout.toString,
      "AttributeName" -> "All",
      "Version" -> "2012-11-05"
    ) ++ waitTimeSeconds.fold(List.empty[(String, String)]) { sec =>
      List("WaitTimeSeconds" -> sec.toString)
    }

    val params = (attributes.zipWithIndex.toList
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
      req <- SignedRequest.post(queue, params, setting.auth).render
      resp <- client
        .expectOr[Elem](req)(handleError)
        .flatMap { xml =>
          val msgs = xml \\ "Message"
          val seq = msgs.map { msg =>
            val md5Body = (msg \ "MD5OfBody").text
            val raw = (msg \ "Body").text
            val attributes = (msg \ "Attribute")
              .map(node => (node \ "Name").text -> (node \ "Value").text)
              .toMap
            val mid = (msg \ "MessageId").text
            val handle = (msg \ "ReceiptHandle").text
            decoder.decode(raw).map { t =>
              ReceiveMessage.Result(mid, handle, t, raw, md5Body, attributes)
            }
          }
          seq.toList.traverse(identity)
        }
    } yield resp
  }
}

object ReceiveMessage {
  case class Result[T](
    messageId: String,
    receiptHandle: String,
    body: T,
    rawBody: String,
    md5OfBody: String,
    attributes: Map[String, String])
}
