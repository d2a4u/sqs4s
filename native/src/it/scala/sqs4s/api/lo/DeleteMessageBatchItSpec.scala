package sqs4s.api.lo

import cats.effect.{Clock, IO}
import fs2.Chunk
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.IOSpec
import sqs4s.api.SqsConfig
import sqs4s.api.errors.AwsSqsError
import sqs4s.auth.Credentials

import scala.concurrent.duration._

class DeleteMessageBatchItSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("DeleteMessageBatch integration test")

  it should "raise error for error response" in {
    val resources = for {
      client <- BlazeClientBuilder[IO](ec).resource
      cred <- Credentials.chain[IO](client)
    } yield (client, cred)
    resources.use {
      case (client, cred) =>
        DeleteMessageBatch[IO](List(DeleteMessageBatch.Entry(
          "1",
          ReceiptHandle("test")
        )))
          .runWith(
            client,
            SqsConfig(
              Uri.unsafeFromString(
                "https://queue.amazonaws.com/123456789012/MyQueue"
              ),
              cred,
              "eu-west-1"
            )
          )
    }
      .attempt
      .unsafeRunSync()
      .swap
      .getOrElse(throw new Exception("Testing failure")) shouldBe a[AwsSqsError]
  }
}
