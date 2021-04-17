package sqs4s.api.lo

import cats.effect.{Clock, IO}
import org.http4s.Uri
import org.http4s.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sqs4s.IOSpec
import sqs4s.auth.Credentials
import sqs4s.api.SqsConfig
import sqs4s.serialization.instances._

import scala.concurrent.duration.TimeUnit
import scala.xml.XML

class ReceiveMessageSpec extends IOSpec {
  val logger = Slf4jLogger.getLogger[IO]

  val testCurrentMillis = 1586623258684L
  val receiptHandle = "123456"
  val accessKey = "ACCESS_KEY"
  val secretKey = "SECRET_KEY"
  val config = SqsConfig(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    credentials = Credentials.basic[IO](accessKey, secretKey),
    region = "eu-west-1"
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("ReceiveMessage")

  it should "create correct request" in {
    val request =
      ReceiveMessage[IO, String](5, 10, Some(10))
        .mkRequest(config, logger)
        .unsafeRunSync()
    val params = request.uri.query.params
    params("Action") shouldEqual "ReceiveMessage"
    params.contains("Version") shouldEqual true
    params("MaxNumberOfMessages").toInt shouldEqual 5
    params("VisibilityTimeout").toInt shouldEqual 10
    params("WaitTimeSeconds").toInt shouldEqual 10
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    val messages = ReceiveMessage[IO, String]()
      .parseResponse {
        val stubbed =
          s"""
           |<ReceiveMessageResponse>
           |  <ReceiveMessageResult>
           |    <Message>
           |      <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
           |      <ReceiptHandle>
           |        MbZj6wDWli+JvwwJaBV+3dcjk2YW2vA3+STFFljTM8tJJg6HRG6PYSasuWXPJB+Cw
           |        Lj1FjgXUv1uSj1gUPAWV66FU/WeR4mq2OKpEGYWbnLmpRCJVAyeMjeU5ZBdtcQ+QE
           |        auMZc8ZRv37sIW2iJKq3M9MFx1YvV11A2x/KSbkJ0=
           |      </ReceiptHandle>
           |      <MD5OfBody>fafb00f5732ab283681e124bf8747ed1</MD5OfBody>
           |      <Body>This is a test message</Body>
           |      <Attribute>
           |        <Name>SenderId</Name>
           |        <Value>195004372649</Value>
           |      </Attribute>
           |      <Attribute>
           |        <Name>SentTimestamp</Name>
           |        <Value>1238099229000</Value>
           |      </Attribute>
           |      <Attribute>
           |        <Name>ApproximateReceiveCount</Name>
           |        <Value>5</Value>
           |      </Attribute>
           |      <Attribute>
           |        <Name>ApproximateFirstReceiveTimestamp</Name>
           |        <Value>1250700979248</Value>
           |      </Attribute>
           |      <MessageAttribute>
           |        <Name>UserDefinedAttribute</Name>
           |        <Value>UserDefinedValue</Value>
           |      </MessageAttribute>
           |    </Message>
           |  </ReceiveMessageResult>
           |  <ResponseMetadata>
           |    <RequestId>b6633655-283d-45b4-aee4-4e84e0ae6afa</RequestId>
           |  </ResponseMetadata>
           |</ReceiveMessageResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()

    messages.size shouldEqual 1
    messages.head.flatMap(_.messageAttributes.get("UserDefinedAttribute")) contains("UserDefinedValue")
  }

  it should "return empty list of response doesn't contain any message" in {
    ReceiveMessage[IO, String]()
      .parseResponse {
        val stubbed =
          s"""
             |<ReceiveMessageResponse xmlns="http://queue.amazonaws.com/doc/2012-11-05/">
             |  <ReceiveMessageResult/><ResponseMetadata>
             |    <RequestId>510518ea-7da8-56ed-972e-4fe62eb7c726</RequestId>
             |  </ResponseMetadata>
             |</ReceiveMessageResponse>
             |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
      .size shouldBe 0
  }
}
