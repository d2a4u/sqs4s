package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.IOSpec
import sqs4s.api.SqsConfig
import sqs4s.errors.AwsSqsError
import sqs4s.auth.Credentials

import scala.concurrent.duration.TimeUnit

class DeleteMessageItSpec extends IOSpec {
  val logger = Slf4jLogger.getLogger[IO]

  val testCurrentMillis = 1586623258684L
  val receiptHandle = ReceiptHandle("123456")

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("DeleteMessage integration test")

  it should "raise error for error response" in {
    val resources = for {
      client <- BlazeClientBuilder[IO](ec).resource
      cred <- Credentials.chain[IO](client)
    } yield (client, cred)
    resources.use {
      case (client, cred) =>
        DeleteMessage[IO](receiptHandle).runWith(
          client,
          SqsConfig(
            Uri.unsafeFromString(
              "https://queue.amazonaws.com/123456789012/MyQueue"
            ),
            cred,
            "eu-west-1"
          ),
          logger
        )
    }.attempt
      .unsafeRunSync()
      .swap
      .getOrElse(throw new Exception("Testing failure")) shouldBe a[AwsSqsError]
  }
}
