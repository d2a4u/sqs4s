package sqs4s.api.lo

import cats.effect.{Clock, IO}
import fs2.Chunk
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api.errors.AwsSqsError
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.internal.aws4.IOSpec
import sqs4s.serialization.instances._

import scala.concurrent.duration._

class SendMessageBatchItSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L
  val receiptHandle = "123456"
  val accessKey = "ACCESS_KEY"
  val secretKey = "SECRET_KEY"
  val settings = SqsSettings(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    AwsAuth(accessKey, secretKey, "eu-west-1")
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("SendMessage integration test")

  it should "raise error for error response" in {
    BlazeClientBuilder[IO](ec).resource
      .use { implicit client =>
        SendMessageBatch[IO, String](Chunk(SendMessageBatch.Entry("1", "test")))
          .runWith(settings)
      }
      .attempt
      .unsafeRunSync()
      .left
      .get shouldBe a[AwsSqsError]
  }
}
