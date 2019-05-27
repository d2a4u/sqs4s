package sqs4s.serialization

abstract class MessageDeserializer[F[_], U, T] {
  def deserialize(u: U): F[T]
}

object MessageDeserializer {
  def instance[F[_], U, T](func: U => F[T]): MessageDeserializer[F, U, T] =
    (u: U) => func(u)
}
