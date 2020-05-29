package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.implicits._
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.internal.aws4.IOSpec

import scala.concurrent.duration.TimeUnit
import scala.xml.XML

class DeleteMessageSpec extends IOSpec {

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

  behavior.of("DeleteMessage")

  it should "create correct request" in {
    val request =
      DeleteMessage[IO](receiptHandle).mkRequest(settings).unsafeRunSync()
    val params = request.uri.query.params
    params.get("Action") shouldEqual Some("DeleteMessage")
    params.contains("Version") shouldEqual true
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    DeleteMessage[IO](receiptHandle)
      .parseResponse {
        val stubbed =
          s"""
           |<DeleteMessageResponse>
           |    <ResponseMetadata>
           |        <RequestId>b5293cb5-d306-4a17-9048-b263635abe42</RequestId>
           |    </ResponseMetadata>
           |</DeleteMessageResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
      .requestId shouldEqual "b5293cb5-d306-4a17-9048-b263635abe42"
  }

  it should "raise error for unexpected response" in {
    DeleteMessage[IO](receiptHandle)
      .parseResponse {
        val stubbed =
          s"""
             |<DeleteMessageResponse>
             |    <ResponseMetadata>
             |        <RequestId></RequestId>
             |    </ResponseMetadata>
             |</DeleteMessageResponse>
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
