package sqs4s

import cats.MonadError
import cats.effect._
import com.amazonaws.services.sqs.AmazonSQSAsync
import javax.jms.{MessageProducer, Session}

private[sqs4s] object ProducerResource extends Connection {
  def resource[F[_]: ConcurrentEffect: Timer: ContextShift](
    queueName: String,
    mode: Int,
    client: AmazonSQSAsync
  ): Resource[F, SqsProducer[F]] = {
    for {
      conn <- connection[F](client)
      sess <- session[F](mode).apply(conn)
      queue <- queue[F](queueName).apply(sess)
      prdc <- javaProducer[F](queue).apply(sess)
      qName = queueName
    } yield new SqsProducer[F](prdc, client) {
      override val queueName: String = qName
    }
  }

  private def javaProducer[F[_]: Sync: MonadError[?[_], Throwable]](
    queue: javax.jms.Queue
  ): Session => Resource[F, MessageProducer] =
    session =>
      Resource.make[F, MessageProducer](
        Sync[F].delay(session.createProducer(queue))
      )(pdc => Sync[F].delay(pdc.close()))
}
