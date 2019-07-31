package sqs4s

import cats.effect._
import com.amazonaws.services.sqs.AmazonSQSAsync
import fs2.Stream
import javax.jms._
import sqs4s.serialization.MessageDecoder

private[sqs4s] object ConsumerResource extends Connection {
  def resourceBytes[F[_]: ConcurrentEffect: Timer: ContextShift, T](
    queueName: String,
    mode: Int,
    internalQueueSize: Int = 20,
    client: AmazonSQSAsync
  )(implicit decoder: MessageDecoder[F, BytesMessage, Stream[F, Byte], T]
  ): Resource[F, SqsConsumer[F, BytesMessage, Stream[F, Byte], T]] =
    javaConsumer[F](queueName, mode, client).map { csm =>
      new SqsConsumer[F, BytesMessage, Stream[F, Byte], T](
        mode,
        internalQueueSize,
        csm
      ) {}
    }

  def resourceStr[F[_]: ConcurrentEffect: Timer: ContextShift, T](
    queueName: String,
    acknowledgeMode: Int,
    internalQueueSize: Int = 20,
    client: AmazonSQSAsync
  )(implicit decoder: MessageDecoder[F, TextMessage, String, T]
  ): Resource[F, SqsConsumer[F, TextMessage, String, T]] =
    javaConsumer[F](queueName, acknowledgeMode, client).map { csm =>
      new SqsConsumer[F, TextMessage, String, T](
        acknowledgeMode,
        internalQueueSize,
        csm
      ) {}
    }

  private def javaConsumer[F[_]: Sync](
    queueName: String,
    acknowledgeMode: Int,
    client: AmazonSQSAsync
  ): Resource[F, MessageConsumer] =
    for {
      conn <- connection[F](client)
      sess <- session[F](acknowledgeMode).apply(conn)
      queue <- queue[F](queueName).apply(sess)
      _ <- Resource.liftF(Sync[F].delay(conn.start()))
      csm <- Resource.make[F, MessageConsumer](
        Sync[F].delay(sess.createConsumer(queue))
      )(csm => Sync[F].delay(csm.close()))
    } yield csm
}
