package sqs4s.native

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}
import fs2.Stream
import io.circe.generic.semiauto._
import io.circe.{parser, _}
import io.circe.syntax._
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import com.danielasfregola.randomdatagenerator.RandomDataGenerator._
import sqs4s.api._
import sqs4s.internal.aws4.IOSpec
import sqs4s.native.serialization.{SqsDeserializer, SqsSerializer}

class ClientSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = IO(0L)
  }

  val accessKey = sys.env("ACCESS_KEY")
  val secretKey = sys.env("SECRET_KEY")
  val awsAccountId = sys.env("AWS_ACCOUNT_ID")
  val queue = Uri.unsafeFromString(
    s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test"
  )
  val settings = SqsSettings(queue, AwsAuth(accessKey, secretKey, "eu-west-1"))

  behavior.of("SQS Consumer and Producer")

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

    def arbStream(n: Long): Stream[IO, TestMessage] = {
      val msg = random[TestMessage]
      Stream
        .random[IO]
        .map { i =>
          msg.copy(int = i)
        }
        .take(n)
    }
  }

  it should "produce and consume messages" in {
    val random = 10L
    val input = TestMessage.arbStream(random)

    val outputF = BlazeClientBuilder[IO](ec)
      .withMaxTotalConnections(100)
      .withMaxWaitQueueLimit(2048)
      .withMaxConnectionsPerRequestKey(Function.const(2048))
      .resource
      .use { implicit client =>
        val producer = SqsProducer.instance[IO, TestMessage](settings)
        val consumer = SqsConsumer.instance[IO, TestMessage](settings)
        // mapAsync number should match connection pool connections
        input
          .mapAsync(2048)(
            msg =>
              producer
                .produce(msg)
          )
          .compile
          .drain
          .flatMap(_ => consumer.dequeueAsync(2048).take(random).compile.drain)
      }
    val o = outputF.unsafeRunSync()
    o shouldBe a[Unit]
  }
}
