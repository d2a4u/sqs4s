package sqs4s

import cats.MonadError
import cats.implicits._

object serialization {

  abstract class SqsDeserializer[F[_]: MonadError[?[_], Throwable], T] {
    def deserialize(s: String): F[T]
  }

  abstract class SqsSerializer[T] {
    def serialize(t: T): String
  }

  object instances {
    implicit def stringSqsDeserializer[F[_]: MonadError[?[_], Throwable]] =
      new SqsDeserializer[F, String] {
        def deserialize(s: String): F[String] = s.pure[F]
      }

    implicit val stringSqsSerializer = new SqsSerializer[String] {
      def serialize(t: String): String = t
    }
  }
}
