package sqs4s.api.lo

import cats.effect.{Clock, IO}
import fs2.Chunk
import org.http4s.Uri
import org.http4s.implicits._
import sqs4s.IOSpec
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.api.{AwsAuth, SqsSettings}

import scala.concurrent.duration._
import scala.xml.XML

class DeleteMessageBatchSpec extends IOSpec {

  val testCurrentMillis = 1586623258684L
  val receiptHandle = "123456"
  val accessKey = "ACCESS_KEY"
  val secretKey = "SECRET_KEY"
  val settings = SqsSettings(
    Uri.unsafeFromString("https://queue.amazonaws.com/123456789012/MyQueue"),
    AwsAuth(accessKey, secretKey, "eu-west-1")
  )
  val attr = Map("foo" -> "1", "bar" -> "2")
  val batch = List(
    DeleteMessageBatch.Entry("1", ReceiptHandle("test1")),
    DeleteMessageBatch.Entry("2", ReceiptHandle("test2"))
  )

  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.pure(testCurrentMillis)

    def monotonic(unit: TimeUnit): IO[Long] = IO(testCurrentMillis)
  }

  behavior.of("SendMessageBatch")

  it should "create correct request" in {
    val request =
      DeleteMessageBatch[IO](batch)
        .mkRequest(settings)
        .unsafeRunSync()
    val params = request.uri.query.params
    params("Action") shouldEqual "DeleteMessageBatch"
    params.contains("Version") shouldEqual true
    params("DeleteMessageBatchRequestEntry.1.Id") shouldEqual "1"
    params("DeleteMessageBatchRequestEntry.2.Id") shouldEqual "2"
    params("DeleteMessageBatchRequestEntry.1.ReceiptHandle") shouldEqual "test1"
    params("DeleteMessageBatchRequestEntry.2.ReceiptHandle") shouldEqual "test2"
    request.headers.exists(_.name == "Expires".ci) shouldEqual true
  }

  it should "parse successful response" in {
    val resp = DeleteMessageBatch[IO](batch)
      .parseResponse {
        val stubbed =
          s"""
           |<DeleteMessageBatchResponse>
           |    <DeleteMessageBatchResult>
           |        <DeleteMessageBatchResultEntry>
           |            <Id>msg1</Id>
           |        </DeleteMessageBatchResultEntry>
           |        <DeleteMessageBatchResultEntry>
           |            <Id>msg2</Id>
           |        </DeleteMessageBatchResultEntry>
           |    </DeleteMessageBatchResult>
           |    <ResponseMetadata>
           |        <RequestId>d6f86b7a-74d1-4439-b43f-196a1e29cd85</RequestId>
           |    </ResponseMetadata>
           |</DeleteMessageBatchResponse>
           |""".stripMargin
        XML.loadString(stubbed)
      }
      .unsafeRunSync()
    val successes = resp.successes.toArray
    resp.requestId shouldEqual "d6f86b7a-74d1-4439-b43f-196a1e29cd85"
    successes(0) shouldEqual DeleteMessageBatch.Success("msg1")
    successes(1) shouldEqual DeleteMessageBatch.Success("msg2")
  }

  it should "raise error for unexpected response" in {
    DeleteMessageBatch[IO](batch)
      .parseResponse {
        val stubbed =
          s"""
             |<DeleteMessageBatchResponse>
             |    <DeleteMessageBatchResult>
             |    </DeleteMessageBatchResult>
             |    <ResponseMetadata>
             |        <RequestId>d6f86b7a-74d1-4439-b43f-196a1e29cd85</RequestId>
             |    </ResponseMetadata>
             |</DeleteMessageBatchResponse>
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
