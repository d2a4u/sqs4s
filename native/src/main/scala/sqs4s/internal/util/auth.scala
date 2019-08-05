package sqs4s.internal.util

import java.time.{Instant, LocalDate, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Credentials, Request}
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString

case class ServiceSetting(
  accessKey: String,
  secretKey: String,
  region: String,
  service: String)

object auth {

  import canonical._
  import signature._
  import common._

  def withAuthHeader[F[_]: Sync: Clock](
    request: Request[F],
    accessKey: String,
    secretKey: String,
    region: String,
    service: String
  ): F[Request[F]] =
    withAuthHeader[F](
      request,
      ServiceSetting(
        accessKey,
        secretKey,
        region,
        service
      )
    )

  def withAuthHeader[F[_]: Sync: Clock](
    request: Request[F],
    setting: ServiceSetting
  ): F[Request[F]] = {
    for {
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      zoneId <- Sync[F].delay(ZoneId.systemDefault())
      now <- Sync[F].delay(Instant.ofEpochMilli(millis).atZone(zoneId))
      headers = request.headers.toList.map(h => (h.name.value, h.value))
      canonicalReq <- canonicalRequest[F](request, now)
      sig <- sign[F](
        setting.secretKey,
        setting.region,
        setting.service,
        canonicalReq
      )
      tk <- token[F](headers, sig, now.toLocalDate, setting)
    } yield {
      request.putHeaders(
        Authorization(Credentials.Token(CaseInsensitiveString(AwsAlgo), tk))
      )
    }
  }

  def token[F[_]: Sync](
    headers: List[(String, String)],
    signature: String,
    now: LocalDate,
    setting: ServiceSetting
  ): F[String] =
    for {
      scope <- credScope[F](now, setting.region, setting.service)
      sh = signedHeaders(headers)
    } yield {
      Map(
        "Credential" -> s"${setting.accessKey}/$scope",
        "SignedHeaders" -> sh,
        "Signature" -> signature
      ).mkString(", ")
    }
}
