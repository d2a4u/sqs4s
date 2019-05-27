package sqs4s.serialization

import cats.Monad
import cats.implicits._

abstract class MessageDecoder[F[_]: Monad, M, U, T] {
  def from(msg: M): F[U]
  def deserialize(u: U): F[T]
  def decode(msg: M): F[T] = from(msg).flatMap(deserialize)
}

object MessageDecoder {
  def apply[F[_]: Monad, M, U, T](
    implicit
    ev: MessageDecoder[F, M, U, T]
  ): MessageDecoder[F, M, U, T] = ev
}
