package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.IOSpec
import sqs4s.api.SqsConfig
import sqs4s.api.errors.AwsSqsError
import sqs4s.auth.Credentials

import scala.concurrent.duration.TimeUnit

class CreateQueueItSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L
  val queueUrl = "https://queue.amazonaws.com/123456789012/MyQueue"
  val sqsEndpoint = Uri.unsafeFromString("https://sqs.eu-west-1.amazonaws.com/")

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("CreateQueue integration test")

  it should "raise error for error response" in {
    val resources = for {
      client <- BlazeClientBuilder[IO](ec).resource
      cred <- Credentials.chain[IO](client)
    } yield (client, cred)
    resources.use {
      case (client, cred) =>
        val config = SqsConfig(Uri.unsafeFromString(""), cred, "eu-west-1")
        CreateQueue[IO]("test", sqsEndpoint).runWith(client, config)
    }.attempt
      .unsafeRunSync()
      .swap
      .getOrElse(throw new Exception("Testing failure")) shouldBe a[AwsSqsError]
  }
}
