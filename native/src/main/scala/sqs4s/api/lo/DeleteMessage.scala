package sqs4s.api.lo

import cats.effect.{Clock, Sync, Timer}
import cats.syntax.all._
import org.http4s.Request
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

final case class DeleteMessage[F[_]: Sync: Clock: Timer](
  receiptHandle: ReceiptHandle
) extends Action[F, DeleteMessage.Result] {

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val params = List(
      "Action" -> "DeleteMessage",
      "ReceiptHandle" -> receiptHandle.value
    ) ++ version

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).render(logger)
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
  final case class Result(requestId: String)
}
