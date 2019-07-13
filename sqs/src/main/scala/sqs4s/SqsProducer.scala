package sqs4s

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
import sqs4s.serialization.MessageEncoder

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
    Sync[F].delay(client.send(m))
  }

  def multiple[T, U, M <: Message](
    msgs: Stream[F, T]
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, Unit] =
    msgs.evalMap { t =>
      for {
        msg <- encoder.encode(t)
        _ <- Sync[F].delay(client.send(msg))
      } yield ()
    }

  def batch[T, U, M <: TextMessage](
    msgs: Stream[F, (String, T)],
    batchSize: Int,
    batchWithin: FiniteDuration = 5.seconds
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, SendMessageBatchResult] =
    for {
      url <- Stream.eval(
        Sync[F].delay(sqsClient.getQueueUrl(queueName).getQueueUrl())
      )
      req <- batchRequests(url, msgs, batchSize, batchWithin)
      res <- Stream.eval(Sync[F].delay(sqsClient.sendMessageBatch(req)))
    } yield res

  def attemptBatch[T, U, M <: TextMessage](
    msgs: Stream[F, (String, T)],
    batchSize: Int,
    batchWithin: FiniteDuration = 5.seconds
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, Either[Throwable, SendMessageBatchResult]] =
    for {
      url <- Stream.eval(
        Sync[F].delay(sqsClient.getQueueUrl(queueName).getQueueUrl())
      )
      req <- batchRequests(url, msgs, batchSize, batchWithin)
      res <- Stream.attemptEval(Sync[F].delay(sqsClient.sendMessageBatch(req)))
    } yield res

  private def batchRequests[T, U, M <: TextMessage](
    url: String,
    msgs: Stream[F, (String, T)],
    batchSize: Int,
    batchWithin: FiniteDuration = 5.seconds
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): Stream[F, SendMessageBatchRequest] =
    msgs.groupWithin(batchSize, batchWithin).evalMap { chunk =>
      chunk
        .traverse(toEntry)
        .map { entries =>
          new SendMessageBatchRequest(url)
            .withEntries(entries.toList.asJava)
        }
    }

  private def toEntry[T, U, M <: TextMessage](
    msg: (String, T)
  )(implicit encoder: MessageEncoder[F, T, U, M]
  ): F[SendMessageBatchRequestEntry] =
    encoder.encode(msg._2).map { m =>
      new SendMessageBatchRequestEntry()
        .withId(msg._1)
        .withMessageBody(m.getText)
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
