package sqs4s.internal.aws4

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter

import cats.effect.Sync
import cats.implicits._
import fs2._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

object common {

  private[aws4] val Algo = "HmacSHA256"
  private[aws4] val AwsAlgo = "AWS4-HMAC-SHA256"
  private[aws4] val Sha256 = "SHA-256"
  private[aws4] val DateTimeFormat =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
  private[aws4] val NewLine = "\n"

  private[aws4] def hmacSha256[F[_]: Sync](
    key: Array[Byte],
    data: String
  ): F[Array[Byte]] =
    Sync[F].delay(Mac.getInstance(Algo)).map { mac =>
      mac.init(new SecretKeySpec(key, Algo))
      mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
    }

  private[aws4] def sha256HexDigest[F[_]: Sync](data: String): F[String] =
    sha256HexDigest[F](data.getBytes(StandardCharsets.UTF_8))

  private[aws4] def sha256HexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    sha256HexDigest[F](Stream.chunk(Chunk.array(data)))

  private[aws4] def sha256HexDigest[F[_]: Sync](
    data: Stream[F, Byte]
  ): F[String] =
    data
      .fold(MessageDigest.getInstance(Sha256)) {
        case (msg, byte) =>
          msg.update(byte)
          msg
      }
      .evalMap(msg => hexDigest[F](msg.digest()))
      .compile
      .string

  private[aws4] def hexDigest[F[_]: Sync](data: Array[Byte]): F[String] =
    Sync[F].delay {
      DatatypeConverter
        .printHexBinary(data)
        .toLowerCase
    }
}
