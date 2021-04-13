package sqs4s

import cats.Applicative
import cats.syntax.all._

object serialization {
  sealed trait SqsDeserializer[F[_], T] {
    def deserialize(s: String): F[T]
  }

  object SqsDeserializer {
    def apply[F[_], T](
      implicit ev: SqsDeserializer[F, T]
    ): SqsDeserializer[F, T] = ev

    def instance[F[_], T](
      f: String => F[T]
    ): SqsDeserializer[F, T] =
      new SqsDeserializer[F, T] {
        override def deserialize(s: String): F[T] = f(s)
      }
  }

  sealed trait SqsSerializer[T] {
    def serialize(t: T): String
  }

  object SqsSerializer {
    def apply[T](
      implicit ev: SqsSerializer[T]
    ): SqsSerializer[T] = ev

    def instance[T](f: T => String): SqsSerializer[T] =
      new SqsSerializer[T] {
        override def serialize(t: T): String = f(t)
      }
  }

  object instances {
    implicit def stringSqsDeserializer[F[_]: Applicative]
      : SqsDeserializer[F, String] =
      new SqsDeserializer[F, String] {
        def deserialize(s: String): F[String] = s.pure[F]
      }

    implicit final val stringSqsSerializer: SqsSerializer[String] =
      new SqsSerializer[String] {
        def serialize(t: String): String = t
      }
  }
}
