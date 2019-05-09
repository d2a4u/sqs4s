package sqs4s

import javax.jms.Message

case class SqsMessage[T, M <: Message](
  value: T,
  private val original: M)
