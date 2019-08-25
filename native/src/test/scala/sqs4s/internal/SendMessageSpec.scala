package sqs4s.internal

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}
import cats.implicits._
import org.scalatest._
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api.{MessageSent, SendMessage, SqsSetting}
import sqs4s.internal.aws4.IOSpec
import sqs4s.serialization.MessageEncoder

import scala.concurrent.duration._

@Ignore
class SendMessageSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = ???
  }

  val accessKey = sys.env("ACCESS_KEY")
  val secretKey = sys.env("SECRET_KEY")
  val awsAccountId = sys.env("AWS_ACCOUNT_ID")

  "SendMessage" should "send a message to SQS" in {
    val setting = SqsSetting(
      "https://sqs.eu-west-1.amazonaws.com/",
      accessKey,
      secretKey,
      "eu-west-1"
    )

    implicit val encoder = new MessageEncoder[IO, String, String, String] {
      override def to(u: String): IO[String] = u.pure[IO]

      override def serialize(t: String): IO[String] = t.pure[IO]
    }
    val created = BlazeClientBuilder[IO](ec).resource
      .use { implicit client =>
        SendMessage[IO, String](
          "test",
          s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test",
          Map("foo" -> "1"),
          Some(1.second),
          None
        ).runWith(setting)
      }
      .unsafeRunSync()
    created shouldBe a[MessageSent]
  }
}
