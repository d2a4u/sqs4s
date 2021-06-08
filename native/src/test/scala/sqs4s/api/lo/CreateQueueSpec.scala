package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.IOSpec
import sqs4s.auth.Credentials
import sqs4s.api.SqsConfig
import sqs4s.errors.UnexpectedResponseError

import scala.concurrent.duration.TimeUnit
import scala.xml.XML

class CreateQueueSpec extends IOSpec {
  val logger = Slf4jLogger.getLogger[IO]

  val testCurrentMillis = 1586623258684L
  val queueUrl = "https://queue.amazonaws.com/123456789012/MyQueue"
  val accessKey = "ACCESS_KEY"
  val secretKey = "SECRET_KEY"
  val sqsEndpoint = Uri.unsafeFromString("https://sqs.eu-west-1.amazonaws.com/")
  val config = SqsConfig(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    credentials = Credentials.basic[IO](accessKey, secretKey),
    region = "eu-west-1"
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("CreateQueue")

  it should "create correct request" in {
    val request =
      CreateQueue[IO]("test", sqsEndpoint)
        .mkRequest(config, logger)
        .unsafeRunSync()
    val params = request.uri.query.params
    params.get("Action") shouldEqual Some("CreateQueue")
    params.get("QueueName") shouldEqual Some("test")
    params.contains("Version") shouldEqual true
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    CreateQueue[IO]("test", sqsEndpoint)
      .parseResponse {
        val stubbed =
          s"""
           |<CreateQueueResponse>
           |    <CreateQueueResult>
           |        <QueueUrl>$queueUrl</QueueUrl>
           |    </CreateQueueResult>
           |    <ResponseMetadata>
           |        <RequestId>7a62c49f-347e-4fc4-9331-6e8e7a96aa73</RequestId>
           |    </ResponseMetadata>
           |</CreateQueueResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
      .queueUrl shouldEqual queueUrl
  }

  it should "raise error for unexpected response" in {
    CreateQueue[IO]("test", sqsEndpoint)
      .parseResponse {
        val stubbed =
          s"""
             |<CreateQueueResponse>
             |    <CreateQueueResult>
             |        <QueueUrl></QueueUrl>
             |    </CreateQueueResult>
             |    <ResponseMetadata>
             |        <RequestId>7a62c49f-347e-4fc4-9331-6e8e7a96aa73</RequestId>
             |    </ResponseMetadata>
             |</CreateQueueResponse>
             |""".stripMargin
        XML.loadString(stubbed)
      }
      .attempt
      .unsafeRunSync()
      .swap
      .getOrElse(throw new Exception("Testing failure")) shouldBe a[
      UnexpectedResponseError
    ]
  }
}
