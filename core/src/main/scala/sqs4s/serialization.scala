package sqs4s

import cats.Monad
import cats.implicits._

object serialization {

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

  abstract class MessageDeserializer[F[_], U, T] {
    def deserialize(u: U): F[T]
  }

  object MessageDeserializer {
    def instance[F[_], U, T](func: U => F[T]): MessageDeserializer[F, U, T] =
      new MessageDeserializer[F, U, T] {
        def deserialize(u: U): F[T] = func(u)
      }
  }

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

  abstract class MessageSerializer[F[_], T, U] {
    def serialize(t: T): F[U]
  }

  object MessageSerializer {
    def instance[F[_], T, U](func: T => F[U]): MessageSerializer[F, T, U] =
      new MessageSerializer[F, T, U] {
        def serialize(t: T): F[U] = func(t)
      }
  }
}
