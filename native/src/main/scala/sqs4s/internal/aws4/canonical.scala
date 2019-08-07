package sqs4s.internal.aws4

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

import cats.effect.Sync
import cats.implicits._
import fs2._
import org.http4s.{Method, Request, Uri}

import scala.language.postfixOps

object canonical {

  import common._

  private val EmptyString = ""
  private val XAmzDate = "x-amz-date"

  implicit val sortHeaders: Ordering[(String, String)] =
    (x: (String, String), y: (String, String)) =>
      (x, y) match {
        case ((kx, vx), (ky, vy)) =>
          val keyOrdering = Ordering[String].compare(kx, ky)
          if (keyOrdering == 0) Ordering[String].compare(vx, vy)
          else keyOrdering
      }

  def canonicalUri[F[_]: Sync](url: String): F[Uri] =
    for {
      ascii <- Sync[F].delay(new URI(url).toASCIIString)
      uri <- Sync[F].fromEither(Uri.fromString(ascii))
    } yield uri

  def canonicalRequest[F[_]: Sync](
    request: Request[F],
    ts: ZonedDateTime
  ): F[String] =
    canonicalRequest[F](
      request.method,
      request.uri,
      request.headers.toList.map(h => (h.name.value, h.value)),
      request.body,
      ts
    )

  def canonicalRequest[F[_]: Sync](
    method: Method,
    uri: Uri,
    headers: List[(String, String)],
    payload: Stream[F, Byte],
    ts: ZonedDateTime
  ): F[String] =
    canonicalRequest[F](
      method,
      uri.path,
      uri.query.toList.map {
        case (k, v) =>
          k -> v.getOrElse("")
      },
      headers,
      payload,
      ts
    )

  def canonicalRequest[F[_]: Sync](
    method: Method,
    url: String,
    headers: List[(String, String)],
    payload: Option[Array[Byte]],
    ts: ZonedDateTime
  ): F[String] =
    for {
      canonicalUri <- canonicalUri[F](url)
      pl = payload.getOrElse(EmptyString.getBytes(StandardCharsets.UTF_8))
      strPl = Stream.emits[F, Byte](pl)
      req <- canonicalRequest[F](method, canonicalUri, headers, strPl, ts)
    } yield req

  def canonicalRequest[F[_]: Sync](
    method: Method,
    url: String,
    headers: List[(String, String)],
    payload: Stream[F, Byte],
    ts: ZonedDateTime
  ): F[String] =
    for {
      canonicalUri <- canonicalUri[F](url)
      req <- canonicalRequest[F](method, canonicalUri, headers, payload, ts)
    } yield req

  def canonicalRequest[F[_]: Sync](
    method: Method,
    path: String,
    query: List[(String, String)],
    headers: List[(String, String)],
    payload: Stream[F, Byte],
    ts: ZonedDateTime
  ): F[String] =
    for {
      canonicalHds <- canonicalHeaders[F](headers, method, ts)
      canonicalQueries <- canonicalQueryString[F](query)
      canonicalHdsStr = canonicalHeadersString(canonicalHds)
      signedHds = signedHeaders(canonicalHds)
      hashedPl <- sha256HexDigest[F](payload)
    } yield {
      method + NewLine +
        path + NewLine +
        canonicalQueries + NewLine +
        canonicalHdsStr + NewLine +
        signedHds + NewLine +
        hashedPl
    }

  private[aws4] def signedHeaders(
    canonicalHeaders: List[(String, String)]
  ): String =
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
      val raw = headers.filterNot {
        case (k, _) =>
          k.equalsIgnoreCase("date") || k.equalsIgnoreCase(XAmzDate)
      } :+ (XAmzDate -> now)

      val rich = raw
        .map {
          case (k, _) if k.equalsIgnoreCase("connection") =>
            k -> "close"
          case (k, v)
              if k
                .equalsIgnoreCase("Content-Length") && v == "0" && !method.name
                .equalsIgnoreCase("post") =>
            k -> ""
          case (k, v) =>
            k -> v.trim.replaceAll(" +", " ")
        }

      val duplicationGuarded: List[(String, String)] => Map[String, String] =
        _.groupBy(_._1).mapValues(_.map(_._2).mkString(","))

      val multiLineGuarded: Map[String, String] => Map[String, String] =
        _.mapValues(_.replaceAll("\n +", ",").trim)

      duplicationGuarded
        .andThen(multiLineGuarded)
        .apply(rich)
        .map {
          case (k, v) =>
            k.toLowerCase -> v
        }
        .toList
        .sorted
    }

  private def canonicalHeadersString(
    headers: List[(String, String)]
  ): String = {
    def concat(k: String, v: String) = s"$k:$v"

    headers.map(concat _ tupled).mkString(NewLine) + NewLine
  }

  private def canonicalQueryString[F[_]: Sync](
    queries: List[(String, String)]
  ): F[String] =
    Sync[F].delay {
      // URL encode but revert unreserved characters
      def urlEncode(str: String): String = {
        URLEncoder
          .encode(str, StandardCharsets.UTF_8.name())
          .replaceAll("\\+", "%20")
          .replaceAll("\\%21", "!")
          .replaceAll("\\%27", "'")
          .replaceAll("\\%28", "(")
          .replaceAll("\\%29", ")")
          .replaceAll("\\%7E", "~")
      }
      queries.sorted
        .map {
          case (key, value) =>
            urlEncode(key) + "=" + urlEncode(value)
        }
        .mkString("&")
    }
}
