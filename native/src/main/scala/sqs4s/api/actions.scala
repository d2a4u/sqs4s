package sqs4s.api

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Header, Headers, Method, Request, Uri}
import sqs4s.api.CreateQueue.defaults._
import sqs4s.internal.aws4.common.IsoDateTimeFormat
import sqs4s.internal.models.CReq

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.Elem

case class SqsSetting(
  url: String,
  accessKey: String,
  secretKey: String,
  region: String)

trait Action

case class CreateQueue(
  name: String,
  delaySeconds: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod)
    extends Action {

  def run[F[_]: Sync: Clock](
    setting: SqsSetting,
    client: Client[F]
  ): F[String] = {
    val attributes = List(
      "DelaySeconds" -> delaySeconds.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString
    ).sortBy {
      case (key, _) => key
    }

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
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      zoneId <- Sync[F].delay(ZoneId.systemDefault())
      now <- Sync[F].delay(Instant.ofEpochMilli(millis).atZone(zoneId))
      fmt <- Sync[F].delay(
        DateTimeFormatter.ofPattern(IsoDateTimeFormat)
      )
      ts <- Sync[F].delay(now.format(fmt))
      expires <- Sync[F].delay(now.plusHours(1).format(fmt))
      _ = println("==========================" + ts)
      uri <- Sync[F].fromEither(Uri.fromString(setting.url))
      uriWithQueries = queryQueries.foldLeft(uri) {
        case (u, (key, value)) =>
          u.withQueryParam(key, value)
      }
      get = Request[F](
        method = Method.GET,
        uri = uriWithQueries,
        headers = {
          uri.host
            .map { h =>
              Headers.of(
                Header("Expires", expires),
                Header("Host", h.value)
              )
            }
            .getOrElse {
              Headers.of(
                Header("Expires", expires)
              )
            }
        }
      )
      creq = CReq[F](get)
      authed <- creq.withAuthHeader(
        setting.accessKey,
        setting.secretKey,
        setting.region,
        "sqs"
      )
      vv <- creq.value
      _ = println("Canonical: " + vv)
      _ = println("==========================")
      _ = println(
        s"""Request(method=${authed.method}, uri=${authed.uri}, headers=${authed.headers}"""
      )
      resp <- client
        .expectOr[Elem](authed) {
          case resp =>
            for {
              bytes <- resp.body.compile.toChunk
              str = new String(bytes.toArray)
              _ = println(s"code: ${resp.status}, response: $str")
            } yield new Exception(s"code: ${resp.status}, response: $str")
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

case class SendMessage[T](message: T)
