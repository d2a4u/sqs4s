package sqs4s.auth

import java.time.Instant

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.Decoder
import org.http4s.EntityDecoder
import org.http4s.circe.jsonOf

import scala.util.Try

private[sqs4s] final case class CredentialResponse(
  accessKeyId: String,
  secretAccessKey: String,
  token: String,
  lastUpdated: Option[Instant],
  expiration: Instant
)

object CredentialResponse {
  private implicit val decodeInstant: Decoder[Instant] =
    Decoder.decodeString.emapTry {
      str =>
        Try(Instant.parse(str))
    }

  implicit val decoder: Decoder[CredentialResponse] = Decoder.instance {
    cursor =>
      (
        cursor.downField("AccessKeyId").as[String],
        cursor.downField("SecretAccessKey").as[String],
        cursor.downField("Token").as[String],
        cursor.downField("LastUpdated").as[Option[Instant]],
        cursor.downField("Expiration").as[Instant]
      ).mapN(CredentialResponse.apply)
  }

  implicit def entityDecoder[F[_]: Concurrent]
    : EntityDecoder[F, List[CredentialResponse]] =
    jsonOf
}
