package sqs4s.api.lo

import cats.implicits._
import cats.effect.{Clock, Sync}
import org.http4s.{Method, Request, Uri}
import org.http4s.client.Client
import org.http4s.scalaxml._
import sqs4s.api.SqsSetting
import sqs4s.api.errors.SqsError
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

import scala.xml.{Elem, XML}

case class DeleteMessage[F[_]: Sync: Clock](
  queueUrl: String,
  receiptHandle: String)
    extends Action[F, DeleteMessage.Result] {

  def runWith(
    setting: SqsSetting
  )(implicit client: Client[F]
  ): F[DeleteMessage.Result] = {
    val queries = List(
      "Action" -> "DeleteMessage",
      "ReceiptHandle" -> receiptHandle,
      "Version" -> "2012-11-05"
    ).sortBy {
      case (key, _) => key
    }

    for {
      uri <- Sync[F].fromEither(Uri.fromString(queueUrl))
      uriWithQueries = queries.foldLeft(uri) {
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
          DeleteMessage.Result(xml \@ "RequestId")
        }
    } yield resp
  }
}

object DeleteMessage {
  case class Result(requestId: String)
}
