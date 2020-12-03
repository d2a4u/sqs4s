package sqs4s.api.hi

import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO, Resource}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.IOSpec
import sqs4s.api.lo.DeleteMessageBatch
import sqs4s.api.{SqsConfig, _}
import sqs4s.auth.Credentials

import scala.concurrent.duration._

class ClientItSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = timer.clock

  val awsAccountId = sys.env("AWS_ACCOUNT_ID")
  val sqsRootEndpoint =
    Uri.unsafeFromString("https://sqs.eu-west-1.amazonaws.com/")
  val region = "eu-west-1"

  behavior.of("SQS Consumer and Producer")

  val clientResource = BlazeClientBuilder[IO](ec)
    .withMaxTotalConnections(256)
    .withMaxWaitQueueLimit(2048)
    .withMaxConnectionsPerRequestKey(_ => 2048)
    .resource

  val producerConsumerResource =
    for {
      client <- clientResource
      cred <- Credentials.chain(client)
      rootConfig = SqsConfig(sqsRootEndpoint, cred, region)
      consumerProducer <- TestUtil.queueResource[IO](client, rootConfig).map {
        queue =>
          val uri = Uri.unsafeFromString(queue.queueUrl)
          val conf = SqsConfig(uri, cred, region)
          val producer = SqsProducer[TestMessage](client, conf)
          val consumer = SqsConsumer[TestMessage](
            client,
            ConsumerConfig(
              conf.queue,
              conf.credentials,
              conf.region,
              waitTimeSeconds = Some(1),
              pollingRate = 2.seconds
            )
          )
          (producer, consumer)
      }
    } yield consumerProducer

  it should "batchProduce dequeueAsync messages" in {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs)

    val dequeueAsync =
      producerConsumerResource.use {
        case (producer, consumer) =>
          producer
            .batchProduce(input, _.int.toString.pure[IO]).compile.drain >>
            consumer.dequeueAsync(256).take(numOfMsgs).compile.toList
      }

    dequeueAsync.unsafeRunSync().size shouldBe numOfMsgs
  }

  it should "batchProduce consumeAsync messages" in {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    val ref = Resource.liftF(Ref.of[IO, List[TestMessage]](List.empty))

    val resources = for {
      r <- ref
      interrupter <- Resource.liftF(SignallingRef[IO, Boolean](false))
      producerConsumer <- producerConsumerResource
    } yield (r, interrupter, producerConsumer._1, producerConsumer._2)

    val consumeAsync = resources.use {
      case (ref, interrupter, producer, consumer) =>
        producer
          .batchProduce(inputStream, _.int.toString.pure[IO])
          .compile
          .drain >>
          consumer
            .consumeAsync(256)(msg => {
              def loop(): IO[Unit] = {
                ref.access.flatMap {
                  case (list, setter) =>
                    val set = setter(list :+ msg).flatMap { updated =>
                      if (updated) {
                        ().pure[IO]
                      } else {
                        loop()
                      }
                    }
                    if (list.size == numOfMsgs - 1)
                      set >> interrupter.set(true)
                    else set
                }
              }
              loop()
            })
            .interruptWhen(interrupter)
            .compile
            .drain >> ref.get
    }

    consumeAsync
      .unsafeRunSync() should contain theSameElementsAs input
  }

  it should "produce and dequeueAsync messages" in {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    val dequeueAsync =
      producerConsumerResource.use {
        case (producer, consumer) =>
          inputStream.mapAsync(256)(producer.produce(_)).compile.drain >>
            consumer.dequeueAsync(256).take(numOfMsgs).compile.toList
      }

    dequeueAsync.unsafeRunSync() should contain theSameElementsAs input
  }

  it should "produce, readsAsync and ack messages" in {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    def reads(ref: Ref[IO, List[TestMessage]]) =
      producerConsumerResource.use {
        case (producer, consumer) =>
          inputStream.mapAsync(256)(producer.produce(_)).compile.drain >>
            consumer.readsAsync(256).evalTap(
              msg => ref.update(_ :+ msg.body)
            ).map(_.receiptHandle).through(
              consumer.ack
            ).take(numOfMsgs).compile.drain >> ref.get
      }

    Ref.of[IO, List[TestMessage]](List.empty).flatMap(
      reads
    ).unsafeRunSync() should contain theSameElementsAs input
  }

  it should "produce, reads and batchAck messages" in {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    val resources = for {
      ref <- Resource.liftF(Ref.of[IO, List[TestMessage]](List.empty))
      interrupter <- Resource.liftF(SignallingRef[IO, Boolean](false))
      producerConsumer <- producerConsumerResource
    } yield (ref, interrupter, producerConsumer._1, producerConsumer._2)

    val batchAck = resources.use {
      case (ref, interrupter, producer, consumer) =>
        inputStream.mapAsync(256)(producer.produce(_)).compile.drain >>
          consumer.reads.evalTap { msg =>

            def loop(): IO[Unit] = {
              ref.access.flatMap {
                case (list, setter) =>
                  if (list.contains(msg)) {
                    ().pure[IO]
                  } else {
                    val set = setter(list :+ msg.body).flatMap {
                      case true =>
                        ().pure[IO]
                      case false =>
                        loop()
                    }
                    if (list.size == numOfMsgs - 1)
                      set >> interrupter.set(true)
                    else set
                  }
              }
            }
            loop()
          }.map { msg =>
            DeleteMessageBatch.Entry(
              msg.messageId,
              msg.receiptHandle
            )
          }.through(
            consumer.batchAck(256)
          ).interruptWhen(interrupter)
            .compile
            .drain >> ref.get
    }

    batchAck.unsafeRunSync() should contain theSameElementsAs input
  }
}
