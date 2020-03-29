package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.native.serialization.SqsDeserializer
import sqs4s.api.SqsSettings

import scala.xml.Elem

case class ReceiveMessage[F[_]: Sync: Clock, T](
  maxNumberOfMessages: Int = 10, // max 10 per sqs api doc
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None
)(implicit decoder: SqsDeserializer[F, T])
    extends Action[F, List[ReceiveMessage.Result[T]]] {

  def runWith(
    setting: SqsSettings
  )(implicit client: Client[F]
  ): F[List[ReceiveMessage.Result[T]]] = {
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

    for {
      req <- SignedRequest.post(params, setting.queue, setting.auth).render
      resp <- client
        .expectOr[Elem](req)(handleError)
        .flatMap { xml =>
          val msgs = xml \\ "Message"
          msgs.toList.traverse { msg =>
            val md5Body = (msg \ "MD5OfBody").text
            val raw = (msg \ "Body").text
            val attributes = (msg \ "Attribute")
              .map(node => (node \ "Name").text -> (node \ "Value").text)
              .toMap
            val mid = (msg \ "MessageId").text
            val handle = (msg \ "ReceiptHandle").text
            decoder.deserialize(raw).map { t =>
              ReceiveMessage.Result(mid, handle, t, raw, md5Body, attributes)
            }
          }
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
