package sqs4s.internal

import java.time.Instant

import cats.effect.{Clock, IO}
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api.{CreateQueue, SqsSetting}
import sqs4s.internal.aws4.IOSpec

import scala.concurrent.duration.TimeUnit

class CreateQueueSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = ???
  }

  val AwsAccountId = "209369606763"
  "CreateQueue" should "create queue when run" in {
    val setting = SqsSetting(
      "https://sqs.eu-west-1.amazonaws.com/",
      "AKIATBP3GVJVRPDG3W4C",
      "U/9MjFLamkzYmIylDIC7nklsHpLZpJXz3nT5CetR",
      "eu-west-1"
    )

    val created = BlazeClientBuilder[IO](ec).resource
      .use { client =>
        CreateQueue("test").run[IO](setting, client)
      }
      .unsafeRunSync()
    println(created)
    created shouldBe a[String]
  }
}
