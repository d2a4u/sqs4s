package sqs4s.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time._
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Credentials.Token
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Headers, Method, Query, Request, Uri}

import scala.language.postfixOps

private[sqs4s] object models {
  import aws4.common._

  final case class CQuery(query: Query) {
    private def urlEncode(str: String): String = {
      URLEncoder
        .encode(str, StandardCharsets.UTF_8.name())
        .replaceAll("\\+", "%20")
        .replaceAll("\\%21", "!")
        .replaceAll("\\%27", "'")
        .replaceAll("\\%28", "(")
        .replaceAll("\\%29", ")")
        .replaceAll("\\%7E", "~")
    }

    val value: String =
      query.toList.sorted
        .map {
          case (k, v) =>
            urlEncode(k) + "=" + urlEncode(v.getOrElse(""))
        }
        .mkString("&")
  }

  final case class CHeaders(headers: Headers, method: Method) {
    val canonical: List[(String, String)] = {
      val kvs = headers.toList.map(h => (h.name.value, h.value))

      val removeDateHeader: List[(String, String)] => List[(String, String)] =
        _.filterNot {
          case (k, _) =>
            k.equalsIgnoreCase("date")
        }

      val pruneHeaders: List[(String, String)] => List[(String, String)] =
        _.map {
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

      removeDateHeader
        .andThen(pruneHeaders)
        .andThen(duplicationGuarded)
        .andThen(multiLineGuarded)
        .apply(kvs)
        .map {
          case (k, v) =>
            k.toLowerCase -> v
        }
        .toList
        .sorted
    }

    val value: String = {
      def concat(k: String, v: String) = s"$k:$v"

      canonical.map(concat _ tupled).mkString(NewLine) + NewLine
    }
  }

  final case class SHeaders(cHeaders: CHeaders) {
    val value: String =
      cHeaders.canonical
        .map {
          case (key, _) => key.toLowerCase
        }
        .mkString(";")
  }

  final case class CReq[F[_]: Sync: Clock](request: Request[F]) {
    lazy val uri: Uri = request.uri
    lazy val method: Method = request.method
    lazy val path: String = uri.path
    lazy val canonicalQuery: CQuery = CQuery(request.uri.query)
    lazy val canonicalHeaders: CHeaders =
      CHeaders(request.headers, request.method)
    lazy val hashedBody: F[String] = sha256HexDigest[F](request.body)
    lazy val signedHeaders: String = SHeaders(canonicalHeaders).value

    lazy val value: F[String] = hashedBody.map { h =>
      List(
        method.name,
        path,
        canonicalQuery.value,
        canonicalHeaders.value,
        signedHeaders,
        h
      ).mkString(NewLine)
    }

    def sign(
      secretKey: String,
      region: String,
      service: String,
      timestamp: LocalDateTime
    ): F[String] =
      for {
        canonicalReq <- value
        key <- signingKey[F](secretKey, region, service, timestamp.toLocalDate)
        sts <- stringToSign[F](region, service, canonicalReq, timestamp)
        sha <- hmacSha256[F](key, sts)
        signed <- hexDigest(sha)
      } yield signed

    def toAuthorizedRequest(
      accessKey: String,
      secretKey: String,
      region: String,
      service: String
    ): F[Request[F]] =
      for {
        millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
        ts <- Sync[F].delay {
          val now = Instant.ofEpochMilli(millis)
          LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        }
        sig <- sign(
          secretKey,
          region,
          service,
          ts
        )
        tk <- token(
          sig,
          signedHeaders,
          ts.toLocalDate,
          accessKey,
          region,
          service
        )
      } yield request.putHeaders(Authorization(tk))

    private def token(
      signature: String,
      signedHeaders: String,
      now: LocalDate,
      accessKey: String,
      region: String,
      service: String
    ): F[Token] =
      for {
        scope <- credScope[F](now, region, service)
      } yield {
        val tk = List(
          "Credential" -> s"$accessKey/$scope",
          "SignedHeaders" -> signedHeaders,
          "Signature" -> signature
        ).map(kv => s"${kv._1}=${kv._2}").mkString(", ")
        Token(CaseInsensitiveString(AwsAlgo), tk)
      }
  }
}
