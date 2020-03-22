package sqs4s.internal

import java.time.Instant

import cats.effect.{Clock, IO}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.Uri
import org.scalatest.Ignore
import sqs4s.api.lo.CreateQueue
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.internal.aws4.IOSpec

import scala.concurrent.duration.TimeUnit

@Ignore
class CreateQueueSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = ???
  }

  val accessKey = sys.env("ACCESS_KEY")
  val secretKey = sys.env("SECRET_KEY")

  val sqsEndpoint = Uri.unsafeFromString("https://sqs.eu-west-1.amazonaws.com/")
  "CreateQueue" should "create queue when run" in {
    val setting = SqsSettings(null, AwsAuth(accessKey, secretKey, "eu-west-1"))

    val created = BlazeClientBuilder[IO](ec).resource
      .use { implicit client =>
        CreateQueue[IO]("test", sqsEndpoint).runWith(setting)
      }
      .unsafeRunSync()
    println(created)
    created shouldBe a[String]
  }
}
