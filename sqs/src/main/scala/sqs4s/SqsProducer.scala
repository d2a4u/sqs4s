package sqs4s

import cats.MonadError
import cats.effect._
import cats.implicits._
import com.amazonaws.services.sqs.AmazonSQSAsync
import javax.jms._
import fs2._
import sqs4s.serialization.MessageEncoder

import scala.util.Try

abstract class SqsProducer[F[_]: Sync](client: MessageProducer) {

  def single[T, U, M <: Message](
    msg: T
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): F[Unit] = encoder.encode(msg).flatMap { m =>
    MonadError[F, Throwable].fromTry(Try(client.send(m)))
  }

  def multiple[T, U, M <: Message](
    msgs: Stream[F, T]
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, Unit] =
    msgs.evalMap { t =>
      for {
        msg <- encoder.encode(t)
        _ <- Sync[F].suspend {
          MonadError[F, Throwable].fromTry(Try(client.send(msg)))
        }
      } yield ()
    }
}

object SqsProducer {
  def resource[F[_]: ConcurrentEffect: Timer: ContextShift](
    queueName: String,
    mode: Int,
    client: AmazonSQSAsync
  ): Resource[F, SqsProducer[F]] =
    ProducerResource.resource[F](queueName, mode, client)
}
