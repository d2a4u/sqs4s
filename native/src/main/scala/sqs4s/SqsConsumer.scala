package sqs4s.native

import cats.effect.{Clock, Concurrent}
import cats.implicits._
import fs2._
import org.http4s.client.Client
import sqs4s.api.lo.{DeleteMessage, ReceiveMessage}
import sqs4s.api.{ConsumerSettings, SqsSettings}
import sqs4s.native.serialization.SqsDeserializer

trait SqsConsumer[F[_], T] {
  def consume(process: T => F[Unit]): F[Unit]
  def dequeue(): Stream[F, T]
  def peek(number: Int): Stream[F, T]
}

object SqsConsumer {
  def instance[F[_]: Concurrent: Clock: Client, T: SqsDeserializer[F, ?]](
    settings: SqsSettings,
    consumerSettings: ConsumerSettings = ConsumerSettings.default
  ) = new SqsConsumer[F, T] {

    override def consume(process: T => F[Unit]): F[Unit] = {
      def ack(proc: T => F[Unit]): Pipe[F, ReceiveMessage.Result[T], Unit] =
        _.flatMap { res =>
          val processed = proc(res.body).flatMap { _ =>
            DeleteMessage[F](res.receiptHandle)
              .runWith(settings)
              .as(())
          }
          Stream.eval(processed)
        }

      val read = Stream.repeatEval(read1).flatMap(Stream.emits)
      read.broadcastThrough(ack(process)).compile.drain
    }

    override def dequeue(): Stream[F, T] = {
      val read = Stream.repeatEval(read1).flatMap(Stream.emits)
      val delete: Pipe[F, ReceiveMessage.Result[T], T] =
        _.flatMap { res =>
          val r = DeleteMessage[F](res.receiptHandle)
            .runWith(settings)
            .as(res.body)
          Stream.eval(r)
        }
      read.broadcastThrough(delete)
    }

    override def peek(number: Int): Stream[F, T] = {
      Stream
        .eval(read1)
        .repeatN(((number / consumerSettings.maxRead) + 1).toLong)
        .flatMap(l => Stream.emits[F, T](l.map(_.body)))
    }

    private def read1 =
      ReceiveMessage[F, T](
        consumerSettings.maxRead,
        consumerSettings.visibilityTimeout,
        consumerSettings.waitTimeSeconds
      ).runWith(settings)
  }
}
