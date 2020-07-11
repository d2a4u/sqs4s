package sqs4s.api.lo

import cats.effect.{Clock, IO}
import fs2.Chunk
import org.http4s.Uri
import org.http4s.implicits._
import sqs4s.IOSpec
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.serialization.instances._

import scala.concurrent.duration._
import scala.xml.XML

class SendMessageBatchSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L
  val receiptHandle = "123456"
  val accessKey = "ACCESS_KEY"
  val secretKey = "SECRET_KEY"
  val settings = SqsSettings(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    AwsAuth(accessKey, secretKey, "eu-west-1")
  )
  val attr = Map("foo" -> "1", "bar" -> "2")
  val batch = Chunk(
    SendMessageBatch.Entry(
      "1",
      "test1",
      attr,
      Some(2.seconds),
      Some("dedup1"),
      Some("group1")
    ),
    SendMessageBatch.Entry(
      "2",
      "test2",
      attr,
      Some(2.seconds),
      Some("dedup2"),
      Some("group2")
    )
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("SendMessageBatch")

  it should "create correct request" in {
    val request =
      SendMessageBatch[IO, String](batch)
        .mkRequest(settings)
        .unsafeRunSync()
    val params = request.uri.query.params
    params("Action") shouldEqual "SendMessageBatch"
    params.contains("Version") shouldEqual true
    params("SendMessageBatchRequestEntry.1.Id") shouldEqual "1"
    params("SendMessageBatchRequestEntry.2.Id") shouldEqual "2"
    params("SendMessageBatchRequestEntry.1.MessageBody") shouldEqual "test1"
    params("SendMessageBatchRequestEntry.2.MessageBody") shouldEqual "test2"
    attr(
      params("SendMessageBatchRequestEntry.1.MessageAttribute.1.Name")
    ) shouldEqual params(
      "SendMessageBatchRequestEntry.1.MessageAttribute.1.Value"
    )
    attr(
      params("SendMessageBatchRequestEntry.1.MessageAttribute.2.Name")
    ) shouldEqual params(
      "SendMessageBatchRequestEntry.1.MessageAttribute.2.Value"
    )
    attr(
      params("SendMessageBatchRequestEntry.2.MessageAttribute.1.Name")
    ) shouldEqual params(
      "SendMessageBatchRequestEntry.2.MessageAttribute.1.Value"
    )
    attr(
      params("SendMessageBatchRequestEntry.2.MessageAttribute.2.Name")
    ) shouldEqual params(
      "SendMessageBatchRequestEntry.2.MessageAttribute.2.Value"
    )
    params("SendMessageBatchRequestEntry.1.DelaySeconds") shouldEqual "2"
    params("SendMessageBatchRequestEntry.2.DelaySeconds") shouldEqual "2"
    params(
      "SendMessageBatchRequestEntry.1.MessageDeduplicationId"
    ) shouldEqual "dedup1"
    params(
      "SendMessageBatchRequestEntry.2.MessageDeduplicationId"
    ) shouldEqual "dedup2"
    params("SendMessageBatchRequestEntry.1.MessageGroupId") shouldEqual "group1"
    params("SendMessageBatchRequestEntry.2.MessageGroupId") shouldEqual "group2"
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    val resp = SendMessageBatch[IO, String](batch)
      .parseResponse {
        val stubbed =
          s"""
           |<SendMessageBatchResponse>
           |<SendMessageBatchResult>
           |    <SendMessageBatchResultEntry>
           |        <Id>test_msg_001</Id>
           |        <MessageId>0a5231c7-8bff-4955-be2e-8dc7c50a25fa</MessageId>
           |        <MD5OfMessageBody>0e024d309850c78cba5eabbeff7cae71</MD5OfMessageBody>
           |    </SendMessageBatchResultEntry>
           |    <SendMessageBatchResultEntry>
           |        <Id>test_msg_002</Id>
           |        <MessageId>15ee1ed3-87e7-40c1-bdaa-2e49968ea7e9</MessageId>
           |        <MD5OfMessageBody>7fb8146a82f95e0af155278f406862c2</MD5OfMessageBody>
           |        <MD5OfMessageAttributes>295c5fa15a51aae6884d1d7c1d99ca50</MD5OfMessageAttributes>
           |    </SendMessageBatchResultEntry>
           |</SendMessageBatchResult>
           |<ResponseMetadata>
           |    <RequestId>ca1ad5d0-8271-408b-8d0f-1351bf547e74</RequestId>
           |</ResponseMetadata>
           |</SendMessageBatchResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
    val successes = resp.successes.toArray
    resp.requestId shouldEqual "ca1ad5d0-8271-408b-8d0f-1351bf547e74"
    successes(0) shouldEqual SendMessageBatch.Success(
      "test_msg_001",
      "0e024d309850c78cba5eabbeff7cae71",
      None,
      "0a5231c7-8bff-4955-be2e-8dc7c50a25fa",
      None
    )
    successes(1) shouldEqual SendMessageBatch.Success(
      "test_msg_002",
      "7fb8146a82f95e0af155278f406862c2",
      Some("295c5fa15a51aae6884d1d7c1d99ca50"),
      "15ee1ed3-87e7-40c1-bdaa-2e49968ea7e9",
      None
    )
  }

  it should "raise error for unexpected response" in {
    SendMessageBatch[IO, String](batch)
      .parseResponse {
        val stubbed =
          s"""
             |<SendMessageBatchResponse>
             |<SendMessageBatchResult>
             |</SendMessageBatchResult>
             |<ResponseMetadata>
             |    <RequestId>ca1ad5d0-8271-408b-8d0f-1351bf547e74</RequestId>
             |</ResponseMetadata>
             |</SendMessageBatchResponse>
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
