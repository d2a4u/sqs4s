package sqs4s.auth

import java.time.Instant

import cats.implicits._
import cats.effect.Sync
import io.circe.Decoder
import org.http4s.EntityDecoder
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder

import scala.util.Try

case class Credential(
  accessKeyId: String,
  secretAccessKey: String,
  token: String,
  lastUpdated: Instant,
  expiration: Instant
)

object Credential {
  private val decodeInstant: Decoder[Instant] = Decoder.decodeString.emapTry {
    str =>
      Try(Instant.parse(str))
  }

  implicit val decoder: Decoder[Credential] = Decoder.instance {
    cursor =>
      (
        cursor.downField("AccessKeyId").as[String],
        cursor.downField("SecretAccessKey").as[String],
        cursor.downField("Token").as[String],
        cursor.downField("LastUpdated").as[Instant](decodeInstant),
        cursor.downField("Expiration").as[Instant](decodeInstant)
      ).mapN(Credential.apply)
  }

  implicit def entityDecoder[F[_]: Sync]: EntityDecoder[F, List[Credential]] =
    circeEntityDecoder[F, List[Credential]]
}
