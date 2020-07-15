package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.IOSpec
import sqs4s.api.errors.AwsSqsError
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.serialization.instances._

import scala.concurrent.duration.TimeUnit

class ReceiveMessageItSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L
  val receiptHandle = "123456"
  val accessKey = "AWS_ACCESS_KEY_ID"
  val secretKey = "AWS_SECRET_KEY"
  val settings = SqsSettings(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    AwsAuth(accessKey, secretKey, "eu-west-1")
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("ReceiveMessage integration test")

  it should "raise error for error response" in {
    BlazeClientBuilder[IO](ec).resource
      .use { client =>
        ReceiveMessage[IO, String]().runWith(client, settings)
      }
      .attempt
      .unsafeRunSync()
      .swap
      .getOrElse(throw new Exception("Testing failure")) shouldBe a[AwsSqsError]
  }
}
