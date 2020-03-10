package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.api.SqsSetting

import scala.xml.Elem

case class DeleteMessage[F[_]: Sync: Clock](queue: Uri, receiptHandle: String)
    extends Action[F, DeleteMessage.Result] {

  def runWith(
    setting: SqsSetting
  )(implicit client: Client[F]
  ): F[DeleteMessage.Result] = {
    val params = List(
      "Action" -> "DeleteMessage",
      "ReceiptHandle" -> receiptHandle,
      "Version" -> "2012-11-05"
    ).sortBy {
      case (key, _) => key
    }

    for {
      req <- SignedRequest.post(queue, params, setting.auth).render
      resp <- client
        .expectOr[Elem](req)(handleError)
        .map { xml =>
          DeleteMessage.Result(xml \@ "RequestId")
        }
    } yield resp
  }
}

object DeleteMessage {
  case class Result(requestId: String)
}
