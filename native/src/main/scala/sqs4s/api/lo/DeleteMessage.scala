package sqs4s.api.lo

import cats.effect.{Clock, Sync, Timer}
import cats.implicits._
import org.http4s.Request
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

case class DeleteMessage[F[_]: Sync: Clock: Timer](receiptHandle: String)
    extends Action[F, DeleteMessage.Result] {

  def mkRequest(config: SqsConfig[F]): F[Request[F]] = {
    val params = List(
      "Action" -> "DeleteMessage",
      "ReceiptHandle" -> receiptHandle,
      "Version" -> "2012-11-05"
    )

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).flatMap(_.render)
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
