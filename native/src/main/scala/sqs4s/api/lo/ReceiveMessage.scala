package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Request
import sqs4s.serialization.SqsDeserializer
import sqs4s.api.SqsSettings
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

case class ReceiveMessage[F[_]: Sync: Clock, T](
  maxNumberOfMessages: Int = 10, // max 10 per sqs api doc
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None
)(implicit decoder: SqsDeserializer[F, T])
    extends Action[F, List[ReceiveMessage.Result[T]]] {

  def mkRequest(settings: SqsSettings): F[Request[F]] = {
    val queries = List(
      "Action" -> "ReceiveMessage",
      "MaxNumberOfMessages" -> maxNumberOfMessages.toString,
      "VisibilityTimeout" -> visibilityTimeout.toString,
      "AttributeName" -> "All",
      "Version" -> "2012-11-05"
    ) ++ waitTimeSeconds.toList.map { sec =>
      "WaitTimeSeconds" -> sec.toString
    }

    val params = queries.sortBy {
      case (key, _) => key
    }

    SignedRequest.post(params, settings.queue, settings.auth).render
  }

  def parseResponse(response: Elem): F[List[ReceiveMessage.Result[T]]] = {
    val msgs = response \\ "Message"
    if (msgs.nonEmpty) {
      msgs.toList.traverse { msg =>
        val md5Body = (msg \ "MD5OfBody").text
        val raw = (msg \ "Body").text
        val attributes = (msg \ "Attribute")
          .map(node => (node \ "Name").text -> (node \ "Value").text)
          .toMap
        val mid = (msg \ "MessageId").text
        val handle = (msg \ "ReceiptHandle").text
        handle.nonEmpty
          .guard[Option]
          .as {
            decoder.deserialize(raw).map { t =>
              ReceiveMessage.Result(mid, handle, t, raw, md5Body, attributes)
            }
          }
          .getOrElse {
            Sync[F].raiseError(
              UnexpectedResponseError("ReceiptHandle", response)
            )
          }
      }
    } else {
      List.empty[ReceiveMessage.Result[T]].pure[F]
    }
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
