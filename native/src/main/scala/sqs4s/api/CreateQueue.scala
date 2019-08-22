package sqs4s.api

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.CreateQueue.defaults._
import sqs4s.api.errors.SqsError
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

import scala.concurrent.duration._
import scala.xml.{Elem, XML}

case class CreateQueue[F[_]: Sync: Clock](
  name: String,
  delay: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod)
    extends Action[F, String] {

  def runWith(setting: SqsSetting)(implicit client: Client[F]): F[String] = {
    val attributes = List(
      "DelaySeconds" -> delay.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString
    )

    val queries = List(
      "Action" -> "CreateQueue",
      "QueueName" -> name,
      "Version" -> "2012-11-05"
    )

    val queryQueries =
      (attributes.zipWithIndex
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
      uri <- Sync[F].fromEither(Uri.fromString(setting.url))
      uriWithQueries = queryQueries.foldLeft(uri) {
        case (u, (key, value)) =>
          u.withQueryParam(key, value)
      }
      get <- Request[F](
        method = Method.GET,
        uri = uriWithQueries
      ).putHostHeader(uriWithQueries)
        .putExpiresHeader[F]()
        .flatMap(_.putXAmzDateHeader[F])
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
        .map(xml => (xml \\ "QueueUrl").text)
    } yield resp
  }
}

object CreateQueue {
  object defaults {
    val DelaySeconds = 0.seconds
    val MaxMessageSize = 262144
    val MessageRetentionPeriod = 4.days
  }
}
