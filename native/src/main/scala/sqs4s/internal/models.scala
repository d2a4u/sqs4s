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

object models {
  import aws4.common._

  case class CQuery(query: Query) {
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

  case class CHeaders(headers: Headers, method: Method) {
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

  case class SHeaders(cHeaders: CHeaders) {
    val value: String =
      cHeaders.canonical
        .map {
          case (key, _) => key.toLowerCase
        }
        .mkString(";")
  }

  case class CReq[F[_]: Sync: Clock](request: Request[F]) {
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

/*
STS -----------------------
AWS4-HMAC-SHA256
20190811T223413Z
20190811/eu-west-1/sqs/aws4_request
8d3e0dd0f95293b81e290e89139c4ed1c7130c4dc483b6aeaf0218d1ab58c332
Canonical: GET
/
Action=CreateQueue&Attribute.1.Name=DelaySeconds&Attribute.1.Value=0&Attribute.2.Name=MaximumMessageSize&Attribute.2.Value=262144&Attribute.3.Name=MessageRetentionPeriod&Attribute.3.Value=345600&QueueName=test&Version=2012-11-05
expires:20190811T214913Z
host:sqs.eu-west-1.amazonaws.com
x-amz-date:20190811T213413Z

expires;host;x-amz-date
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
==========================
Request(method=GET, uri=https://sqs.eu-west-1.amazonaws.com/?Action=CreateQueue&Attribute.1.Name=DelaySeconds&Attribute.1.Value=0&Attribute.2.Name=MaximumMessageSize&Attribute.2.Value=262144&Attribute.3.Name=MessageRetentionPeriod&Attribute.3.Value=345600&QueueName=test&Version=2012-11-05, headers=Headers(Host: sqs.eu-west-1.amazonaws.com, Expires: 20190811T214913Z, x-amz-date: 20190811T213413Z, Authorization: AWS4-HMAC-SHA256 Credential=AKIATBP3GVJVRPDG3W4C/20190811/eu-west-1/sqs/aws4_request, SignedHeaders=expires;host;x-amz-date, Signature=7b9a74061589121190746a0c01e6b5442365f345b3d3cb50e9ef06eaa3f9de17)
code: 403 Forbidden, response: <?xml version="1.0"?><ErrorResponse xmlns="http://queue.amazonaws.com/doc/2012-11-05/"><Error><Type>Sender</Type><Code>SignatureDoesNotMatch</Code><Message>The request signature we calculated does not match the signature you provided. Check your AWS Secret Access Key and signing method. Consult the service documentation for details.

The Canonical String for this request should have been
'GET
/
Action=CreateQueue&amp;Attribute.1.Name=DelaySeconds&amp;Attribute.1.Value=0&amp;Attribute.2.Name=MaximumMessageSize&amp;Attribute.2.Value=262144&amp;Attribute.3.Name=MessageRetentionPeriod&amp;Attribute.3.Value=345600&amp;QueueName=test&amp;Version=2012-11-05
expires:20190811T214913Z
host:sqs.eu-west-1.amazonaws.com
x-amz-date:20190811T213413Z

expires;host;x-amz-date
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

The String-to-Sign should have been
'AWS4-HMAC-SHA256
20190811T213413Z
20190811/eu-west-1/sqs/aws4_request
8d3e0dd0f95293b81e290e89139c4ed1c7130c4dc483b6aeaf0218d1ab58c332'
</Message><Detail/></Error><RequestId>939e11b0-c27c-5ac9-ac13-447a4ef74515</RequestId></ErrorResponse>
 */
