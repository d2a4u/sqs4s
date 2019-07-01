package queue4s

import javax.jms.Message

case class SqsMessage[T, M <: Message](
  value: T,
  original: M)
