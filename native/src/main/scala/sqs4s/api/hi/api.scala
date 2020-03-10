package sqs4s.api.hi

import fs2._

trait api {
  type Queue
  type MessageId

  def consume[F[_], T](queue: Queue): Stream[F, T]

  def produce[F[_], T](queue: String, t: T): F[T]

  def acknowledge[F[_]](id: MessageId): F[Unit]
}
