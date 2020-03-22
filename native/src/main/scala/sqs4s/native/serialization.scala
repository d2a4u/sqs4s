package sqs4s.native

import cats.Monad
import cats.implicits._
import sqs4s.serialization.{MessageDecoder, MessageEncoder}

object serialization {

  abstract class SqsDeserializer[F[_]: Monad, T]
      extends MessageDecoder[F, String, String, T] {
    override def from(msg: String): F[String] = msg.pure[F]
  }

  abstract class SqsSerializer[F[_]: Monad, T]
      extends MessageEncoder[F, T, String, String] {
    override def to(u: String): F[String] = u.pure[F]
  }
}
