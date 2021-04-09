package sqs4s.internal.aws4

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time._
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.syntax.all._
import fs2._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import org.http4s.{Header, Request, Uri}

private[sqs4s] object common {

  val IsoDateTimeFormat = "yyyyMMdd'T'HHmmss'Z'"
  val Algo = "HmacSHA256"
  val AwsAlgo = "AWS4-HMAC-SHA256"
  val Sha256 = "SHA-256"
  val NewLine = "\n"
  val EmptyString = ""
  val XAmzDate = "x-amz-date"
  val Expires = "Expires"
  val Host = "Host"

  def sha256HexDigest[F[_]: Sync](data: Stream[F, Byte]): F[String] =
    data
      .fold(MessageDigest.getInstance(Sha256)) {
        case (msg, byte) =>
          msg.update(byte)
          msg
      }
      .evalMap(msg => hexDigest[F](msg.digest()))
      .compile
      .string

  def canonicalUri[F[_]: Sync](url: String): F[Uri] =
    for {
      ascii <- Sync[F].delay(new URI(url).toASCIIString)
      uri <- Sync[F].fromEither(Uri.fromString(ascii))
    } yield uri

  def stringToSign[F[_]: Sync](
    region: String,
    service: String,
    canonicalRequest: String,
    timestamp: LocalDateTime
  ): F[String] =
    for {
      ts <- Sync[F].delay {
        val fmt = DateTimeFormatter
          .ofPattern(IsoDateTimeFormat)
        timestamp.format(fmt)
      }
      scope <- credScope[F](timestamp.toLocalDate, region, service)
      hashedCr <- sha256HexDigest[F](canonicalRequest)
    } yield List(AwsAlgo, ts, scope, hashedCr).mkString(NewLine)

  def credScope[F[_]: Sync](
    dateStamp: LocalDate,
    region: String,
    service: String
  ): F[String] =
    Sync[F]
      .delay(dateStamp.format(DateTimeFormatter.BASIC_ISO_DATE))
      .map(ds => s"$ds/$region/$service/aws4_request")

  def signingKey[F[_]: Sync](
    secretKey: String,
    region: String,
    service: String,
    date: LocalDate
  ): F[Array[Byte]] =
    for {
      date <- Sync[F].delay(date.format(DateTimeFormatter.BASIC_ISO_DATE))
      signedSecret = s"AWS4$secretKey".getBytes(StandardCharsets.UTF_8)
      signedDate <- hmacSha256[F](signedSecret, date)
      signedRegion <- hmacSha256[F](signedDate, region)
      signedService <- hmacSha256[F](signedRegion, service)
      sig <- hmacSha256[F](signedService, "aws4_request")
    } yield sig

  def hmacSha256[F[_]: Sync](key: Array[Byte], data: String): F[Array[Byte]] =
    Sync[F].delay(Mac.getInstance(Algo)).map { mac =>
      mac.init(new SecretKeySpec(key, Algo))
      mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
    }

  def hexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    Sync[F].delay {
      DatatypeConverter
        .printHexBinary(data)
        .toLowerCase
    }

  private[aws4] def sha256HexDigest[F[_]: Sync](data: String): F[String] =
    sha256HexDigest[F](data.getBytes(StandardCharsets.UTF_8))

  private[aws4] def sha256HexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    sha256HexDigest[F](Stream.chunk(Chunk.array(data)))

  implicit val sortHeaders: Ordering[(String, String)] =
    (x: (String, String), y: (String, String)) =>
      (x, y) match {
        case ((kx, vx), (ky, vy)) =>
          val keyOrdering = Ordering[String].compare(kx, ky)
          if (keyOrdering == 0) Ordering[String].compare(vx, vy)
          else keyOrdering
      }

  implicit class RichRequest[F[_]](request: Request[F]) {
    def withHostHeader(uri: Uri): Request[F] =
      uri.host
        .map(h => request.putHeaders(Header(Host, h.value)))
        .getOrElse(request)

    def withExpiresHeaderF[G[x] >: F[x]: Sync: Clock](
      seconds: Long = 15 * 60
    ): G[Request[F]] =
      for {
        millis <- Clock[G].realTime(TimeUnit.MILLISECONDS)
        expires <- Sync[G].delay {
          val now = Instant.ofEpochMilli(millis)
          val fmt = DateTimeFormatter.ofPattern(IsoDateTimeFormat)
          LocalDateTime
            .ofInstant(now.plusSeconds(seconds), ZoneOffset.UTC)
            .format(fmt)
        }
      } yield {
        request.putHeaders(Header(Expires, expires))
      }

    def withXAmzDateHeaderF[G[x] >: F[x]: Sync](
      currentMillis: Long
    ): G[Request[F]] =
      for {
        ts <- Sync[G].delay {
          val now = Instant.ofEpochMilli(currentMillis)
          val fmt = DateTimeFormatter.ofPattern(IsoDateTimeFormat)
          LocalDateTime.ofInstant(now, ZoneOffset.UTC).format(fmt)
        }
      } yield {
        request.putHeaders(Header(XAmzDate, ts))
      }
  }
}
