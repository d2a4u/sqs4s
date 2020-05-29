package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.implicits._
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.internal.aws4.IOSpec
import sqs4s.serialization.instances._

import scala.concurrent.duration._
import scala.xml.XML

class SendMessageSpec extends IOSpec {

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

  behavior.of("SendMessage")

  it should "create correct request" in {
    val attr = Map("foo" -> "1", "bar" -> "2")
    val request =
      SendMessage[IO, String](
        "test",
        attr,
        Some(2.seconds),
        Some("dedup1"),
        Some("group1")
      ).mkRequest(settings)
        .unsafeRunSync()
    val params = request.uri.query.params
    params("Action") shouldEqual "SendMessage"
    params.get("Version").nonEmpty shouldEqual true
    params("DelaySeconds").toInt shouldEqual 2
    attr(params("MessageAttribute.1.Name")) shouldEqual params(
      "MessageAttribute.1.Value"
    )
    attr(params("MessageAttribute.2.Name")) shouldEqual params(
      "MessageAttribute.2.Value"
    )
    params("MessageBody") shouldEqual "test"
    params("MessageDeduplicationId") shouldEqual "dedup1"
    params("MessageGroupId") shouldEqual "group1"
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    val resp = SendMessage[IO, String]("test")
      .parseResponse {
        val stubbed =
          s"""
           |<SendMessageResponse>
           |    <SendMessageResult>
           |        <MD5OfMessageBody>fafb00f5732ab283681e124bf8747ed1</MD5OfMessageBody>
           |        <MD5OfMessageAttributes>3ae8f24a165a8cedc005670c81a27295</MD5OfMessageAttributes>
           |        <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
           |    </SendMessageResult>
           |    <ResponseMetadata>
           |        <RequestId>27daac76-34dd-47df-bd01-1f6e873584a0</RequestId>
           |    </ResponseMetadata>
           |</SendMessageResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
    resp.requestId shouldEqual "27daac76-34dd-47df-bd01-1f6e873584a0"
    resp.messageId shouldEqual "5fea7756-0ea4-451a-a703-a558b933e274"
    resp.messageBodyMd5 shouldEqual "fafb00f5732ab283681e124bf8747ed1"
    resp.messageAttributesMd5 shouldEqual Some(
      "3ae8f24a165a8cedc005670c81a27295"
    )
  }

  it should "raise error for unexpected response" in {
    SendMessage[IO, String]("test")
      .parseResponse {
        val stubbed =
          s"""
             |<SendMessageResponse>
             |    <SendMessageResult>
             |        <MD5OfMessageBody></MD5OfMessageBody>
             |        <MD5OfMessageAttributes>3ae8f24a165a8cedc005670c81a27295</MD5OfMessageAttributes>
             |        <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
             |    </SendMessageResult>
             |    <ResponseMetadata>
             |        <RequestId>27daac76-34dd-47df-bd01-1f6e873584a0</RequestId>
             |    </ResponseMetadata>
             |</SendMessageResponse>
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
