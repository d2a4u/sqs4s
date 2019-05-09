package sqs4s.serialization

import cats.Monad
import cats.implicits._
import javax.jms.Message

abstract class MessageDecoder[F[_]: Monad, M <: Message, U, T] {
  def from(msg: M): F[U]
  def deserialize(u: U): F[T]
  def decode(msg: M): F[T] = from(msg).flatMap(deserialize)
}

object MessageDecoder extends decoders {
  def apply[F[_]: Monad, M <: Message, U, T](
    implicit
    ev: MessageDecoder[F, M, U, T]
  ): MessageDecoder[F, M, U, T] = ev
}
