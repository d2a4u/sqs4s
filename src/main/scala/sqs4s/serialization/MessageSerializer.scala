package sqs4s.serialization

abstract class MessageSerializer[F[_], T, U] {
  def serialize(t: T): F[U]
}

object MessageSerializer {
  def instance[F[_], T, U](func: T => F[U]): MessageSerializer[F, T, U] =
    (t: T) => func(t)
}
