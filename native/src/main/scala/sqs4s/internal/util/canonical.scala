package sqs4s.internal.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

import cats.effect.Sync
import cats.implicits._
import org.http4s.Method

import scala.language.postfixOps

object canonical {

  import common._

  private val EmptyString = ""
  private val XAmzDate = "x-amz-date"

  def canonicalUri(url: String): String =
    //TODO: implement this properly
    url

  def canonicalRequest[F[_]: Sync](
    method: Method,
    uri: String,
    queries: List[(String, String)],
    headers: List[(String, String)],
    payload: Option[Array[Byte]],
    ts: ZonedDateTime
  ): F[String] =
    for {
      canonicalHds <- canonicalHeaders[F](headers, method, ts)
      canonicalHdsStr = canonicalHeadersString(canonicalHds)
      signedHds = signedHeaders(canonicalHds)
      pl = payload.getOrElse(EmptyString.getBytes(StandardCharsets.UTF_8))
      hashedPl <- sha256HexDigest[F](pl)
    } yield {
      method + NewLine +
        uri + NewLine +
        canonicalQueryString(queries) + NewLine +
        canonicalHdsStr + NewLine +
        signedHds + NewLine +
        hashedPl
    }

  private def signedHeaders(canonicalHeaders: List[(String, String)]): String =
    canonicalHeaders
      .map {
        case (key, _) => key.toLowerCase
      }
      .mkString(";")

  private def canonicalHeaders[F[_]: Sync](
    headers: List[(String, String)],
    method: Method,
    ts: ZonedDateTime
  ): F[List[(String, String)]] =
    Sync[F].delay(ts.format(DateTimeFormat)).map { now =>
      val raw = sorted(headers.distinct.filterNot {
        case (k, _) =>
          k.equalsIgnoreCase("date") || k.equalsIgnoreCase(XAmzDate)
      } :+ (XAmzDate -> now))

      raw.map {
        case (k, _) if k.equalsIgnoreCase("connection") =>
          k -> "close"
        case (k, v)
            if k.equalsIgnoreCase("Content-Length") && v == "0" && !method.name
              .equalsIgnoreCase("post") =>
          k -> ""
        case (k, v) =>
          k -> v.trim.replaceAll(" +", " ")
      }
    }

  private def canonicalHeadersString(
    headers: List[(String, String)]
  ): String = {
    def concat(k: String, v: String) = s"$k:$v"

    headers.map(concat _ tupled).mkString(NewLine) + NewLine
  }

  private def canonicalQueryString(queries: List[(String, String)]): String =
    sorted(queries)
      .map {
        case (key, value) =>
          key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8.toString)
      }
      .mkString("&")

  private def sorted(list: List[(String, String)]): List[(String, String)] =
    list.sortBy {
      case (k, _) => k
    }

}
