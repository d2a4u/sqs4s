package sqs4s.internal.aws4

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{LocalDate, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cats.effect.Sync
import cats.implicits._
import fs2._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter
import org.http4s.Uri

object common {

  val IsoDateTimeFormat = "yyyyMMdd'T'HHmmssZ"
  val Algo = "HmacSHA256"
  val AwsAlgo = "AWS4-HMAC-SHA256"
  val Sha256 = "SHA-256"
  val NewLine = "\n"
  val EmptyString = ""
  val XAmzDate = "x-amz-date"

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
    timestamp: ZonedDateTime
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
    timestamp: ZonedDateTime
  ): F[Array[Byte]] =
    for {
      date <- Sync[F].delay(
        timestamp.toLocalDate.format(DateTimeFormatter.BASIC_ISO_DATE)
      )
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
}
