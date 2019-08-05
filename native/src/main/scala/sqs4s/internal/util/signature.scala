package sqs4s.internal.util

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneId, ZonedDateTime}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._

import scala.language.postfixOps

object signature {

  import common._

  def sign[F[_]: Sync: Clock](
    secretKey: String,
    region: String,
    service: String,
    canonicalReq: String
  ): F[String] = {
    for {
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      zoneId <- Sync[F].delay(ZoneId.systemDefault())
      now <- Sync[F].delay(Instant.ofEpochMilli(millis).atZone(zoneId))
      key <- signingKey[F](secretKey, region, service, now)
      data <- stringToSign[F](region, service, canonicalReq, now)
      sha <- hmacSha256[F](key, data)
      signed <- hexDigest(sha)
    } yield signed
  }

  private[util] def stringToSign[F[_]: Sync](
    region: String,
    service: String,
    canonicalRequest: String,
    timestamp: ZonedDateTime
  ): F[String] =
    for {
      ts <- Sync[F].delay(timestamp.format(DateTimeFormat))
      scope <- credScope[F](timestamp.toLocalDate, region, service)
    } yield List(AwsAlgo, ts, scope, canonicalRequest).mkString(NewLine)

  private[util] def credScope[F[_]: Sync](
    dateStamp: LocalDate,
    region: String,
    service: String
  ): F[String] =
    Sync[F]
      .delay(dateStamp.format(DateTimeFormatter.BASIC_ISO_DATE))
      .map(ds => s"$ds/$region/$service/aws4_request")

  private[util] def signingKey[F[_]: Sync](
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
}
