package sqs4s.api.lo

import cats.effect.{Clock, Sync, Timer}
import cats.implicits._
import org.http4s.{Request, Uri}
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

case class DeleteQueue[F[_]: Sync: Clock: Timer](
  sqsEndpoint: Uri
) extends Action[F, DeleteQueue.Result] {

  def mkRequest(config: SqsConfig[F]): F[Request[F]] = {
    val param = List(
      "Action" -> "DeleteQueue"
    ) ++ version

    SignedRequest.get[F](
      param,
      sqsEndpoint,
      config.credentials,
      config.region
    ).render
  }

  def parseResponse(response: Elem): F[DeleteQueue.Result] = {
    val rid = (response \\ "RequestId").text
    rid.nonEmpty
      .guard[Option]
      .as {
        DeleteQueue.Result(rid).pure[F]
      }
      .getOrElse {
        Sync[F].raiseError(UnexpectedResponseError("RequestId", response))
      }
  }
}

object DeleteQueue {
  case class Result(requestId: String)
}
