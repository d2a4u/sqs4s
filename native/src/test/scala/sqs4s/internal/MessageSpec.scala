package sqs4s.internal

import java.time.Instant
import java.util.concurrent.TimeUnit

import fs2._
import cats.effect.{Clock, IO}
import cats.implicits._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api._
import sqs4s.api.lo.{DeleteMessage, ReceiveMessage, SendMessage}
import sqs4s.internal.aws4.IOSpec
import sqs4s.serialization.{MessageDecoder, MessageEncoder}

import scala.concurrent.duration._

class MessageSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = ???
  }

  val accessKey = sys.env("ACCESS_KEY")
  val secretKey = sys.env("SECRET_KEY")
  val awsAccountId = sys.env("AWS_ACCOUNT_ID")
  val queue = Uri.unsafeFromString(
    s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test"
  )
  val message = "test"

  behavior.of("Message API")

  it should "send and receive message" in {
    val setting = SqsSetting(
      Uri.unsafeFromString("https://sqs.eu-west-1.amazonaws.com/"),
      AwsAuth(accessKey, secretKey, "eu-west-1")
    )

    implicit val encoder = new MessageEncoder[IO, String, String, String] {
      override def to(u: String): IO[String] = u.pure[IO]

      override def serialize(t: String): IO[String] = t.pure[IO]
    }

    implicit val decoder = new MessageDecoder[IO, String, String, String] {
      override def from(m: String): IO[String] = m.pure[IO]

      override def deserialize(t: String): IO[String] = t.pure[IO]
    }

    val inputs = 1 to 10
    BlazeClientBuilder[IO](ec).resource
      .use { implicit client =>
        inputs.toList
          .map(i => SendMessage[IO, String](i.toString, queue))
          .traverse(_.runWith(setting))
      }
      .unsafeRunSync()

    type Result = Either[DeleteMessage.Result, Int]
    val out: Pipe[IO, ReceiveMessage.Result[String], Result] =
      _.map(_.body.toInt.asRight)
    def ack(
      implicit c: Client[IO]
    ): Pipe[IO, ReceiveMessage.Result[String], Result] = _.flatMap { res =>
      Stream.eval(
        DeleteMessage[IO](queue, res.receiptHandle)
          .runWith(setting)
          .map(_.asLeft)
      )
    }

    val polled = Stream
      .resource(BlazeClientBuilder[IO](ec).resource)
      .flatMap { implicit client =>
        val reads = ReceiveMessage[IO, String](queue, 10).runWith(setting).map {
          results =>
            Stream
              .fromIterator[IO, ReceiveMessage.Result[String]](
                results.toIterator
              )
              .broadcastThrough(out, ack)
        }
        Stream
          .repeatEval(reads)
          .metered(1.seconds)
          .interruptAfter(10.seconds)
          .flatten
      }
      .compile
      .toList
      .unsafeRunSync()

    polled.collect {
      case Right(i) => i
    } should contain theSameElementsAs inputs
  }
}
