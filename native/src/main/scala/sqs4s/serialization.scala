package sqs4s

import cats.MonadError
import cats.implicits._

object serialization {

  abstract class SqsDeserializer[F[_]: MonadError[*[_], Throwable], T] {
    def deserialize(s: String): F[T]
  }

  object SqsDeserializer {
    def apply[F[_]: MonadError[*[_], Throwable], T](
      implicit ev: SqsDeserializer[F, T]
    ): SqsDeserializer[F, T] = ev

    def instance[F[_]: MonadError[*[_], Throwable], T](
      f: String => F[T]
    ): SqsDeserializer[F, T] =
      new SqsDeserializer[F, T] {
        override def deserialize(s: String): F[T] = f(s)
      }
  }

  abstract class SqsSerializer[T] {
    def serialize(t: T): String
  }

  object SqsSerializer {
    def apply[T](implicit ev: SqsSerializer[T]): SqsSerializer[T] = ev

    def instance[T](f: T => String): SqsSerializer[T] =
      (t: T) => f(t)
  }

  object instances {
    implicit def stringSqsDeserializer[F[_]: MonadError[*[_], Throwable]] =
      new SqsDeserializer[F, String] {
        def deserialize(s: String): F[String] = s.pure[F]
      }

    implicit val stringSqsSerializer = new SqsSerializer[String] {
      def serialize(t: String): String = t
    }
  }
}
