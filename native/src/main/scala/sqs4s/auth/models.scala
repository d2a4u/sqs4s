package sqs4s.auth

import java.time.Instant

import cats.implicits._
import cats.effect.Sync
import io.circe.Decoder
import org.http4s.EntityDecoder
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder

import scala.util.Try

private[sqs4s] case class CredentialResponse(
  accessKeyId: String,
  secretAccessKey: String,
  token: String,
  lastUpdated: Instant,
  expiration: Instant
)

object CredentialResponse {
  private val decodeInstant: Decoder[Instant] = Decoder.decodeString.emapTry {
    str =>
      Try(Instant.parse(str))
  }

  implicit val decoder: Decoder[CredentialResponse] = Decoder.instance {
    cursor =>
      (
        cursor.downField("AccessKeyId").as[String],
        cursor.downField("SecretAccessKey").as[String],
        cursor.downField("Token").as[String],
        cursor.downField("LastUpdated").as[Instant](decodeInstant),
        cursor.downField("Expiration").as[Instant](decodeInstant)
      ).mapN(CredentialResponse.apply)
  }

  implicit def entityDecoder[F[_]: Sync]
    : EntityDecoder[F, List[CredentialResponse]] =
    circeEntityDecoder[F, List[CredentialResponse]]
}
