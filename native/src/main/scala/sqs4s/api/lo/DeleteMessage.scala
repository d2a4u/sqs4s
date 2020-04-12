package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Request
import sqs4s.api.SqsSettings
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

case class DeleteMessage[F[_]: Sync: Clock](receiptHandle: String)
    extends Action[F, DeleteMessage.Result] {

  def mkRequest(settings: SqsSettings): F[Request[F]] = {
    val params = List(
      "Action" -> "DeleteMessage",
      "ReceiptHandle" -> receiptHandle,
      "Version" -> "2012-11-05"
    ).sortBy {
      case (key, _) => key
    }

    SignedRequest.post(params, settings.queue, settings.auth).render
  }

  def parseResponse(response: Elem): F[DeleteMessage.Result] = {
    val rid = (response \\ "RequestId").text
    rid.nonEmpty
      .guard[Option]
      .as {
        DeleteMessage.Result(rid).pure[F]
      }
      .getOrElse {
        Sync[F].raiseError(UnexpectedResponseError("RequestId", response))
      }
  }
}

object DeleteMessage {
  case class Result(requestId: String)
}
