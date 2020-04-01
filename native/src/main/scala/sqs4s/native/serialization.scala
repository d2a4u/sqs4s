package sqs4s.native

import cats.MonadError

object serialization {

  abstract class SqsDeserializer[F[_]: MonadError[?[_], Throwable], T] {
    def deserialize(s: String): F[T]
  }

  abstract class SqsSerializer[T] {
    def serialize(t: T): String
  }
}
