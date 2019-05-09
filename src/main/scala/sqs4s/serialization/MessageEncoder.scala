package sqs4s.serialization

import cats.Monad
import cats.implicits._
import javax.jms.Message

abstract class MessageEncoder[F[_]: Monad, T, U, M <: Message] {
  def to(u: U): F[M]
  def serialize(t: T): F[U]
  def encode(t: T): F[M] = serialize(t).flatMap(to)
}

object MessageEncoder extends encoders {
  def apply[F[_]: Monad, T, U, M <: Message](
    implicit ev: MessageEncoder[F, T, U, M]
  ): MessageEncoder[F, T, U, M] = ev
}
