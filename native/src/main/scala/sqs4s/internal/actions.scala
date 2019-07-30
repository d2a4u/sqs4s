package sqs4s.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import cats.effect.Sync
import cats.implicits._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Method, Uri}
import sqs4s.internal.CreateQueue.defaults._

import scala.concurrent.duration._
import scala.xml.Elem

case class SqsSetting(url: String)

trait Action

case class CreateQueue(
  name: String,
  delaySeconds: Duration = DelaySeconds,
  maxMessageSize: Int = MaxMessageSize,
  messageRetentionPeriod: Duration = MessageRetentionPeriod)
    extends Action {

  def run[F[_]: Sync](
    implicit setting: SqsSetting,
    client: Client[F]
  ): F[String] = {
    val attributes = List(
      "DelaySeconds" -> delaySeconds.toSeconds.toString,
      "MaximumMessageSize" -> maxMessageSize.toString,
      "MessageRetentionPeriod" -> messageRetentionPeriod.toSeconds.toString,
    ).sortBy {
      case (key, _) => key
    }

    val params = List(
      "Action" -> "CreateQueue",
      "QueueName" -> name,
      "Version" -> "2012-11-05"
    )

    val queryParams =
      (attributes
      .zipWithIndex
      .flatMap {
        case ((key, value), index) =>
          List(s"Attribute.${index + 1}.Name" -> key, s"Attribute.${index + 1}.Value" -> value)
      } ++ params).sortBy {
      case (key, _) => key
    }

    val uri = queryParams.foldLeft(Uri.uri(setting.url)) {
      case (url, (key, value)) =>
        url.withQueryParam(key, value)
    }

    client.expect[Elem](uri).map(xml => (xml \\ "QueueUrl").text)
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

object Signer {
  private val Utf8 = StandardCharsets.UTF_8
  private val Algo = "HmacSHA256"
  private val Base16 = Array[Char]('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  private val NewLine = "\n"
  private val EmptyString = ""

  def sign[F[_]: Sync](
    data: String,
    secretKey: String,
    dateStamp: LocalDate,
    region: String,
    service: String): F[String] = {
    val ds = dateStamp.format(DateTimeFormatter.BASIC_ISO_DATE)
    for {
      key <- signatureKey[F](secretKey, ds, region, service)
      sha <- hmacSHA256[F](data, key)
    } yield base16(sha)
  }

  def canonicalRequest(method: Method, uri: String, params: List[(String, String)]) =
    method + NewLine +
      uri + NewLine +
      urlEncoded(params) + NewLine +
      headersString + NewLine +
      signedHeaderKeys + NewLine +
      base16(hash(payload.getOrElse(EmptyString.getBytes(StandardCharsets.UTF_8))))

  val baseUri = Uri.uri("http://foo.com")
  // baseUri: org.http4s.Uri = http://foo.com

  val withPath = baseUri.withPath("/bar/baz")
  // withPath: org.http4s.Uri = http://foo.com/bar/baz

  val withQuery = withPath.withQueryParam("hello", "world")

  private def urlEncoded(queryParams: List[(String, String)]): String =
    queryParams.map {
      case (key, value) => key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8.toString)
    }.mkString("&")

  private def hmacSHA256[F[_]: Sync](
    data: String,
    key: Array[Byte]
  ): F[Array[Byte]] =
    Sync[F].delay(Mac.getInstance(Algo)).map { mac =>
      mac.init(new SecretKeySpec(key, Algo))
      mac.doFinal(data.getBytes(Utf8))
    }

  private def signatureKey[F[_]: Sync](
    key: String,
    dateStamp: String,
    region: String,
    service: String
  ): F[Array[Byte]] =
    for {
      kSec <- Sync[F].delay(s"AWS4$key".getBytes(Utf8))
      kDate <- hmacSHA256(dateStamp, kSec)
      kRegion <- hmacSHA256(region, kDate)
      kService <- hmacSHA256(service, kRegion)
    } yield hmacSHA256("aws4_request", kService)

  private def base16(data: Array[Byte]): String =
    data.flatMap(byte => Array(Base16(byte >> 4 & 0xF), Base16(byte & 0xF))).mkString
}
