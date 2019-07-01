package queue4s.serialization

import cats.Monad
import cats.implicits._

abstract class MessageEncoder[F[_]: Monad, T, U, M] {
  def to(u: U): F[M]
  def serialize(t: T): F[U]
  def encode(t: T): F[M] = serialize(t).flatMap(to)
}

object MessageEncoder {
  def apply[F[_]: Monad, T, U, M](
    implicit ev: MessageEncoder[F, T, U, M]
  ): MessageEncoder[F, T, U, M] = ev
}
