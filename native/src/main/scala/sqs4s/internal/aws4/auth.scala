package sqs4s.internal.aws4

import java.time.{Instant, LocalDate, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.Credentials.Token
import org.http4s.Request
import org.http4s.headers.Authorization
import org.http4s.util.CaseInsensitiveString
import sqs4s.internal.models.Creq

case class ServiceSetting(
  accessKey: String,
  secretKey: String,
  region: String,
  service: String)

object auth {

  import canonical._
  import common._
  import signature._

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
  ): F[Request[F]] =
    for {
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      zoneId <- Sync[F].delay(ZoneId.systemDefault())
      now <- Sync[F].delay(Instant.ofEpochMilli(millis).atZone(zoneId))
      headers = request.headers.toList.map(h => (h.name.value, h.value))
      canonicalReq <- canonicalRequest[F](request, now)
      sig <- signCreq[F](
        setting.secretKey,
        setting.region,
        setting.service,
        Creq(canonicalReq)
      )
      tk <- token[F](headers, sig, now.toLocalDate, setting)
    } yield request.putHeaders(Authorization(tk))

  def token[F[_]: Sync](
    headers: List[(String, String)],
    signature: String,
    now: LocalDate,
    setting: ServiceSetting
  ): F[Token] =
    for {
      scope <- credScope[F](now, setting.region, setting.service)
      sh = signedHeaders(headers)
    } yield {
      val tk = Map(
        "Credential" -> s"${setting.accessKey}/$scope",
        "SignedHeaders" -> sh,
        "Signature" -> signature
      ).mkString(", ")
      Token(CaseInsensitiveString(AwsAlgo), tk)
    }
}
