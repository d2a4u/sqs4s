package sqs4s

import cats.MonadError
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.amazonaws.services.sqs.AmazonSQSAsync
import fs2._
import fs2.concurrent.Queue
import javax.jms._
import sqs4s.serialization.MessageDecoder

import scala.reflect.{classTag, ClassTag}

abstract class SqsConsumer[
  F[_]: ConcurrentEffect: Timer: ContextShift,
  M <: Message: ClassTag,
  U,
  T
](
  acknowledgeMode: Int,
  internalQueueSize: Int,
  client: MessageConsumer
)(implicit decoder: MessageDecoder[F, M, U, T]) {

  /**
    * Consume SQS messages as a stream,
    * use this if acknowledgeMode is AUTO_ACKNOWLEDGE
    *
    * @return Stream of T
    */
  def consume(): Stream[F, T] =
    if (acknowledgeMode == Session.AUTO_ACKNOWLEDGE) {
      val streamF = for {
        q <- Queue.bounded[F, T](internalQueueSize)
        _ <- consume(q.enqueue)
      } yield q.dequeue
      Stream.eval(streamF).flatten
    } else
      Stream.raiseError[F](
        new JMSException("Only auto acknowledge is supported")
      )

  /**
    * Consume and process SQS messages in callback style,
    * use this if acknowledgeMode is AUTO_ACKNOWLEDGE
    *
    * @return Unit within F context
    */
  def consume(callback: Pipe[F, T, Unit]): F[Unit] =
    if (acknowledgeMode == Session.AUTO_ACKNOWLEDGE) {
      consumeRaw(_.flatMap { m =>
        callback(Stream.eval(decoder.decode(m)))
      })
    } else
      MonadError[F, Throwable].raiseError(
        new JMSException("Only auto acknowledge is supported")
      )

  /**
    * Manually acknowledge SQS message via a pipe
    *
    * @return a Pipe (function to call Java method acknowledge() on a message within F context
    */
  def ack(): Pipe[F, M, Unit] =
    _.evalMap(m => Sync[F].delay(m.acknowledge()))

  /**
    * Consume SQS messages as a stream, but does not acknowledge the message,
    * use this for other acknowledgeMode (not AUTO_ACKNOWLEDGE)
    *
    * @return Stream of SqsMessage where SqsMessage is a wrapper around T and M so that
    *         M is accessible for acknowledge call (via [[ack]])
    */
  def receive(): Stream[F, SqsMessage[T, M]] = {
    val streamF = for {
      q <- Queue.bounded[F, SqsMessage[T, M]](internalQueueSize)
      _ <- receive(q.enqueue)
    } yield q.dequeue
    Stream.eval(streamF).flatten
  }

  def receive(callback: Pipe[F, SqsMessage[T, M], Unit]): F[Unit] =
    consumeRaw(_.flatMap { m =>
      callback(Stream.eval(decoder.decode(m)).map(t => SqsMessage(t, m)))
    })

  def consumeRaw(callback: Pipe[F, M, Unit]): F[Unit] = {
    val filter: Pipe[F, Message, M] = _.collect {
      case m if classTag[M].runtimeClass.isInstance(m) =>
        m.asInstanceOf[M]
    }
    for {
      q <- Queue.bounded[F, Message](internalQueueSize)
      listener <- Sync[F].delay[MessageListener] {
        new MessageListener {
          def onMessage(msg: Message): Unit =
            q.enqueue1(msg).toIO.unsafeRunAsyncAndForget()
        }
      }
      _ <- client.setMessageListener(listener).pure[F]
      _ <- q.dequeue.through(filter).through(callback).compile.drain.start
    } yield ()
  }
}

object SqsConsumer {
  def resourceStr[F[_]: ConcurrentEffect: Timer: ContextShift, T](
    queueName: String,
    mode: Int,
    internalQueueSize: Int = 20,
    client: AmazonSQSAsync
  )(implicit decoder: MessageDecoder[F, TextMessage, String, T]
  ): Resource[F, SqsConsumer[F, TextMessage, String, T]] =
    ConsumerResource
      .resourceStr[F, T](queueName, mode, internalQueueSize, client)

  def resourceBin[F[_]: ConcurrentEffect: Timer: ContextShift, T](
    queueName: String,
    mode: Int,
    internalQueueSize: Int = 20,
    client: AmazonSQSAsync
  )(implicit decoder: MessageDecoder[F, BytesMessage, Stream[F, Byte], T]
  ): Resource[F, SqsConsumer[F, BytesMessage, Stream[F, Byte], T]] =
    ConsumerResource
      .resourceBytes[F, T](queueName, mode, internalQueueSize, client)
}
