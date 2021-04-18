package sqs4s.api.hi

import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO, Resource}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.IOSpec
import sqs4s.api.lo.{DeleteMessageBatch, SendMessageBatch}
import sqs4s.api.{SqsConfig, _}
import sqs4s.auth.Credentials

import scala.concurrent.duration._

class ClientItSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = timer.clock

  val logger = Slf4jLogger.getLogger[IO]

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
      consumerProducer <-
        TestUtil.queueResource[IO](client, rootConfig, logger).map {
          queue =>
            val uri = Uri.unsafeFromString(queue.queueUrl)
            val conf = SqsConfig(uri, cred, region)
            val producer = SqsProducer[TestMessage](client, conf, logger)
            val consumer = SqsConsumer[TestMessage](
              client,
              ConsumerConfig(
                conf.queue,
                conf.credentials,
                conf.region,
                waitTimeSeconds = Some(1),
                pollingRate = 2.seconds
              ),
              logger
            )
            (producer, consumer)
        }
    } yield consumerProducer

  it should "produce and read a message with custom attribute values" in {
    val input = TestMessage.sample
    val av = Map("foo" -> "1", "bar" -> "2")

    val result =
      producerConsumerResource.use {
        case (producer, consumer) =>
          producer.produce(input, av) >> consumer.read
      }.unsafeToFuture()

    result.map {
      case chunk if chunk.head.isDefined =>
        val r = chunk.toList.head
        r.messageId shouldBe a[String]
        r.messageAttributes shouldEqual av

      case _ =>
        fail()
    }.futureValue
  }

  it should "batchProduce dequeueAsync messages" in {
    val numOfMsgs = 22
    val input = TestMessage.arb(numOfMsgs)

    val result =
      producerConsumerResource.use {
        case (producer, consumer) =>
          producer
            .batchProduce(
              Stream.emits(input).covary[IO].map(
                SendMessageBatch.BatchEntry(_)
              ),
              _.int.toString.pure[IO]
            ).compile.drain >>
            consumer.dequeueAsync(256).take(numOfMsgs).compile.toList
      }.unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }

  it should "batchProduce and readsAsync messages with custom attribute values" in {
    val numOfMsgs = 22
    val input = TestMessage.arb(numOfMsgs).zipWithIndex.map {
      case (msg, idx) =>
        SendMessageBatch.BatchEntry(msg, Map("index" -> idx.toString))
    }

    val ref = Resource.eval(Ref.of[
      IO,
      Set[SendMessageBatch.BatchEntry[TestMessage]]
    ](Set.empty))

    val resources = for {
      r <- ref
      interrupter <- Resource.eval(SignallingRef[IO, Boolean](false))
      producerConsumer <- producerConsumerResource
    } yield (r, interrupter, producerConsumer._1, producerConsumer._2)

    val result = resources.use {
      case (ref, _, producer, consumer) =>
        producer
          .batchProduce(Stream.emits(input).covary[IO], _.int.toString.pure[IO])
          .compile
          .drain >> consumer.readsAsync(256).evalTap { msg =>
          ref.update(_ + SendMessageBatch.BatchEntry(
            msg.body,
            msg.messageAttributes
          ))
        }.map(_.receiptHandle).through(
          consumer.ack
        ).take(numOfMsgs).compile.drain >> ref.get
    }.unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }

  it should "batchProduce consumeAsync messages" in {
    val numOfMsgs = 22
    val input =
      TestMessage.arb(numOfMsgs)

    val ref = Resource.eval(Ref.of[IO, List[TestMessage]](List.empty))

    val resources = for {
      r <- ref
      interrupter <- Resource.eval(SignallingRef[IO, Boolean](false))
      producerConsumer <- producerConsumerResource
    } yield (r, interrupter, producerConsumer._1, producerConsumer._2)

    val result = resources.use {
      case (ref, interrupter, producer, consumer) =>
        producer
          .batchProduce(
            Stream.emits(input).covary[IO].map(SendMessageBatch.BatchEntry(_)),
            _.int.toString.pure[IO]
          )
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
    }.unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }

  it should "produce and dequeueAsync messages" in {
    val numOfMsgs = 22
    val input = TestMessage.arb(numOfMsgs)

    val result =
      producerConsumerResource.use {
        case (producer, consumer) =>
          Stream.emits(input).covary[IO].mapAsync(256)(
            producer.produce(_)
          ).compile.drain >>
            consumer.dequeueAsync(256).take(numOfMsgs).compile.toList
      }.unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }

  it should "produce, readsAsync and ack messages" in {
    val numOfMsgs = 22
    val input = TestMessage.arb(numOfMsgs)

    def reads(ref: Ref[IO, List[TestMessage]]) =
      producerConsumerResource.use {
        case (producer, consumer) =>
          Stream.emits(input).covary[IO].mapAsync(256)(
            producer.produce(_)
          ).compile.drain >>
            consumer.readsAsync(256).evalTap(msg =>
              ref.update(_ :+ msg.body)
            ).map(_.receiptHandle).through(
              consumer.ack
            ).take(numOfMsgs).compile.drain >> ref.get
      }

    val result = Ref.of[IO, List[TestMessage]](List.empty).flatMap(
      reads
    ).unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }

  it should "produce, reads and batchAck messages" in {
    val numOfMsgs = 22
    val input = TestMessage.arb(numOfMsgs)

    val resources = for {
      ref <- Resource.eval(Ref.of[IO, List[TestMessage]](List.empty))
      interrupter <- Resource.eval(SignallingRef[IO, Boolean](false))
      producerConsumer <- producerConsumerResource
    } yield (ref, interrupter, producerConsumer._1, producerConsumer._2)

    val result = resources.use {
      case (ref, interrupter, producer, consumer) =>
        Stream.emits(input).covary[IO].mapAsync(256)(
          producer.produce(_)
        ).compile.drain >>
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
    }.unsafeToFuture().futureValue

    result should contain theSameElementsAs input
  }
}
