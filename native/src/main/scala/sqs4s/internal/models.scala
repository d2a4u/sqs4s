package sqs4s.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time._
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Credentials.Token
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Headers, Method, Query, Request, Uri}

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

  case class Sts(value: String) extends AnyVal

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
      timestamp: ZonedDateTime
    ): F[String] =
      for {
        canonicalReq <- value
        key <- signingKey[F](secretKey, region, service, timestamp)
        sts <- stringToSign[F](region, service, canonicalReq, timestamp)
        _ = println("============================== sts: " + sts)
        sha <- hmacSha256[F](key, sts)
        signed <- hexDigest(sha)
      } yield signed

    def withAuthHeader(
      accessKey: String,
      secretKey: String,
      region: String,
      service: String
    ): F[Request[F]] =
      for {
        millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
        zoneId <- Sync[F].delay(ZoneId.systemDefault())
        now <- Sync[F].delay(Instant.ofEpochMilli(millis).atZone(zoneId))
        ts <- Sync[F].delay {
          val fmt = DateTimeFormatter
            .ofPattern(IsoDateTimeFormat)
          now.format(fmt)
        }
        req = request.putHeaders(
          Header(XAmzDate, ts)
        )
        sig <- sign(
          secretKey,
          region,
          service,
          now
        )
        tk <- token(
          sig,
          signedHeaders,
          now.toLocalDate,
          accessKey,
          region,
          service
        )
      } yield req.putHeaders(Authorization(tk))

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
============================== sts: AWS4-HMAC-SHA256
20190811T155553+0100
20190811/eu-west-1/sqs/aws4_request
86f0a8b35f7fa6c56d62c63f12456b29d7cf635b2dacd6ca93cf895e57fca0ec
Canonical:
GET
/209369606763
Action=CreateQueue&amp;Attribute.1.Name=DelaySeconds&amp;Attribute.1.Value=0&amp;Attribute.2.Name=MaximumMessageSize&amp;Attribute.2.Value=262144&amp;Attribute.3.Name=MessageRetentionPeriod&amp;Attribute.3.Value=345600&amp;QueueName=test&amp;Version=2012-11-05
expires:20190811T165553+0100
host:sqs.eu-west-1.amazonaws.com

expires;host
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
==========================
Request(method=GET, uri=https://sqs.eu-west-1.amazonaws.com/209369606763?Action=CreateQueue&Attribute.1.Name=DelaySeconds&Attribute.1.Value=0&Attribute.2.Name=MaximumMessageSize&Attribute.2.Value=262144&Attribute.3.Name=MessageRetentionPeriod&Attribute.3.Value=345600&QueueName=test&Version=2012-11-05, headers=Headers(Expires: 20190811T165553+0100, Host: sqs.eu-west-1.amazonaws.com, x-amz-date: 20190811T155553+0100, Authorization: AWS4-HMAC-SHA256 Credential=AKIATBP3GVJVRPDG3W4C/20190811/eu-west-1/sqs/aws4_request, SignedHeaders=expires;host, Signature=f9c2523b531cf6440403e4ac6f00ade03917a0db6c0e61781388c8cd64f1fd2a)
code: 403 Forbidden, response: <?xml version="1.0"?><ErrorResponse xmlns="http://queue.amazonaws.com/doc/2012-11-05/"><Error><Type>Sender</Type><Code>SignatureDoesNotMatch</Code><Message>The request signature we calculated does not match the signature you provided. Check your AWS Secret Access Key and signing method. Consult the service documentation for details.

The Canonical String for this request should have been
'
GET
/209369606763
Action=CreateQueue&amp;Attribute.1.Name=DelaySeconds&amp;Attribute.1.Value=0&amp;Attribute.2.Name=MaximumMessageSize&amp;Attribute.2.Value=262144&amp;Attribute.3.Name=MessageRetentionPeriod&amp;Attribute.3.Value=345600&amp;QueueName=test&amp;Version=2012-11-05
expires:20190811T165553+0100
host:sqs.eu-west-1.amazonaws.com

expires;host
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

The String-to-Sign should have been
'AWS4-HMAC-SHA256
20190811T145553Z
20190811/eu-west-1/sqs/aws4_request
d7366611a6eed3d8a19cbf2dec813a8622f0ffa30bfd371ec458c865660d7ad8'
</Message><Detail/></Error><RequestId>6d510b70-3a88-524f-b5d6-5eba15a77379</RequestId></ErrorResponse>
 */