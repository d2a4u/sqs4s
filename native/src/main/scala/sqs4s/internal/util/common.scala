package sqs4s.internal.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter

import cats.effect.Sync
import cats.implicits._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

object common {

  private[util] val Algo = "HmacSHA256"
  private[util] val AwsAlgo = "AWS4-HMAC-SHA256"
  private[util] val Sha256 = "SHA-256"
  private[util] val DateTimeFormat =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
  private[util] val NewLine = "\n"

  private[util] def hmacSha256[F[_]: Sync](
    key: Array[Byte],
    data: String
  ): F[Array[Byte]] =
    Sync[F].delay(Mac.getInstance(Algo)).map { mac =>
      mac.init(new SecretKeySpec(key, Algo))
      mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
    }

  private[util] def sha256HexDigest[F[_]: Sync](data: String): F[String] =
    sha256HexDigest[F](data.getBytes(StandardCharsets.UTF_8))

  private[util] def sha256HexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    Sync[F]
      .delay(MessageDigest.getInstance(Sha256).digest(data))
      .flatMap(hexDigest[F])

  private[util] def hexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    Sync[F].delay {
      DatatypeConverter
        .printHexBinary(data)
        .toLowerCase
    }
}
