package queue4s

import cats.effect._
import cats.implicits._
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model.{
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry,
  SendMessageBatchResult
}
import fs2._
import javax.jms._
import queue4s.serialization.MessageEncoder

import scala.collection.JavaConverters._
import scala.concurrent.duration._

abstract class SqsProducer[F[_]: Timer: Concurrent](
  client: MessageProducer,
  sqsClient: AmazonSQSAsync) {

  def queueName: String

  def single[T, U, M <: Message](
    msg: T
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): F[Unit] = encoder.encode(msg).flatMap { m =>
    Async[F].delay(client.send(m))
  }

  def multiple[T, U, M <: Message](
    msgs: Stream[F, T]
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, Unit] =
    msgs.evalMap { t =>
      for {
        msg <- encoder.encode(t)
        _ <- Async[F].delay(client.send(msg))
      } yield ()
    }

  def batch[T, U, M <: TextMessage](
    msgs: Stream[F, (String, T)],
    batchSize: Int,
    batchWithin: FiniteDuration = 5.seconds
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, SendMessageBatchResult] =
    msgs.groupWithin(batchSize, batchWithin).evalMap { chunk =>
      val entries = chunk.traverse {
        case (id, msg) =>
          encoder.encode(msg).map { m =>
            new SendMessageBatchRequestEntry()
              .withId(id)
              .withMessageBody(m.getText)
          }
      }
      for {
        url <- Async[F].delay(sqsClient.getQueueUrl(queueName).getQueueUrl())
        es <- entries
        batchReq = {
          val req = new SendMessageBatchRequest(url)
          req.setEntries(es.toList.asJava)
          req
        }
        result <- Async[F]
          .delay(sqsClient.sendMessageBatchAsync(batchReq).get())
      } yield result
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
