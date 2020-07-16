package sqs4s.api.hi

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO, Resource}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{parser, _}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.scalacheck.{Arbitrary, Gen}
import sqs4s.IOSpec
import sqs4s.api._
import sqs4s.auth.Credentials
import sqs4s.serialization.{SqsDeserializer, SqsSerializer}

import scala.concurrent.duration._

class ClientItSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] =
      IO.delay {
        Instant.now().toEpochMilli
      }

    def monotonic(unit: TimeUnit): IO[Long] = IO(0L)
  }

  val awsAccountId = sys.env("AWS_ACCOUNT_ID")
  val queue = Uri.unsafeFromString(
    s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test"
  )
  val region = "eu-west-1"

  case class TestMessage(string: String, int: Int, boolean: Boolean)

  object TestMessage {
    implicit val encode: Encoder[TestMessage] = deriveEncoder
    implicit val decode: Decoder[TestMessage] = deriveDecoder

    implicit val desrlz = new SqsDeserializer[IO, TestMessage] {
      override def deserialize(u: String): IO[TestMessage] =
        IO.fromEither(parser.decode[TestMessage](u))
    }

    implicit val srlz = new SqsSerializer[TestMessage] {
      override def serialize(t: TestMessage): String =
        t.asJson.noSpaces
    }

    implicit val arbTestMessage: Arbitrary[TestMessage] = {
      val gen = for {
        str <- Gen.alphaNumStr
        int <- Gen.choose(Int.MinValue, Int.MaxValue)
        bool <- Gen.oneOf(Seq(true, false))
      } yield TestMessage(str, int, bool)
      Arbitrary(gen)
    }

    def arbStream(n: Long): Stream[IO, TestMessage] = {
      val msg = arb[TestMessage]
      Stream
        .random[IO]
        .map(i => msg.copy(int = i))
        .take(n)
    }
  }

  behavior.of("SQS Consumer and Producer")

  trait Fixture {
    val clientResrc = BlazeClientBuilder[IO](ec)
      .withMaxTotalConnections(256)
      .withMaxWaitQueueLimit(2048)
      .withMaxConnectionsPerRequestKey(Function.const(2048))
      .resource
  }

  it should "batch produce messages" in new Fixture {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs)

    val result = for {
      client <- Stream.resource(clientResrc)
      cred <- Stream.resource(Credentials.chain[IO](client))
      producer = SqsProducer[TestMessage](
        client,
        SqsConfig(queue, cred, region)
      )
      consumer = SqsConsumer[TestMessage](
        client,
        ConsumerConfig(
          queue,
          cred,
          region,
          waitTimeSeconds = Some(1),
          pollingRate = 2.seconds
        )
      )
      _ <- producer
        .batchProduce(input, _.int.toString.pure[IO])
      output <- consumer.dequeueAsync(256)
    } yield output

    result.take(
      numOfMsgs
    ).compile.toList.unsafeRunSync().size shouldBe numOfMsgs
  }

  it should "batch consume messages" in new Fixture {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    val ref = Resource.liftF(Ref.of[IO, List[TestMessage]](List.empty))

    val resources = for {
      r <- ref
      interrupter <- Resource.liftF(SignallingRef[IO, Boolean](false))
      client <- clientResrc
      cred <- Credentials.chain[IO](client)
    } yield (r, interrupter, client, cred)

    val outputF = resources.use {
      case (ref, interrupter, client, cred) =>
        val producer =
          SqsProducer[TestMessage](client, SqsConfig(queue, cred, region))
        val consumer = SqsConsumer[TestMessage](
          client,
          ConsumerConfig(
            queue,
            cred,
            region,
            waitTimeSeconds = Some(1),
            pollingRate = 2.seconds
          )
        )
        producer
          .batchProduce(inputStream, _.int.toString.pure[IO])
          .compile
          .drain
          .flatMap { _ =>
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
              .drain
          } >> ref.get
    }

    outputF
      .unsafeRunSync() should contain theSameElementsAs input
  }

  it should "produce and dequeue messages" in new Fixture {
    val numOfMsgs = 22L
    val input = TestMessage.arbStream(numOfMsgs).compile.toList.unsafeRunSync()
    val inputStream = Stream[IO, TestMessage](input: _*)

    val consumed = for {
      client <- Stream.resource(clientResrc)
      cred <- Stream.resource(Credentials.chain[IO](client))
      producer = SqsProducer[TestMessage](
        client,
        SqsConfig(queue, cred, region)
      )
      consumer = SqsConsumer[TestMessage](
        client,
        ConsumerConfig(
          queue,
          cred,
          region,
          waitTimeSeconds = Some(1),
          pollingRate = 2.seconds
        )
      )
      _ <- inputStream
        .mapAsync(256)(msg => producer.produce(msg))
      result <- consumer.dequeueAsync(256)
    } yield result

    consumed.take(
      numOfMsgs
    ).compile.toList.unsafeRunSync() should contain theSameElementsAs input
  }
}
