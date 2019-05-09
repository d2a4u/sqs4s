package sqs4s

import cats.effect._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.sqs.{AmazonSQSAsync, AmazonSQSAsyncClientBuilder}
import fs2._
import javax.jms.{BytesMessage, Session, TextMessage}
import org.elasticmq.rest.sqs.{SQSRestServer, SQSRestServerBuilder}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.global

class SqsProducerSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  var server: SQSRestServer = _
  val accessKey = "x"
  val secretKey = "x"
  val testQueueName = "test-queue-void"

  override def beforeAll(): Unit = {
    super.beforeAll()
    server = SQSRestServerBuilder.start()
  }

  override def afterAll(): Unit = {
    server.stopAndWait()
    super.afterAll()
  }

  trait Fixture {
    implicit val timer: Timer[IO] = IO.timer(global)
    implicit val cs: ContextShift[IO] = IO.contextShift(global)

    val client: AmazonSQSAsync =
      AmazonSQSAsyncClientBuilder
        .standard()
        .withCredentials(
          new AWSStaticCredentialsProvider(
            new BasicAWSCredentials(accessKey, secretKey)
          )
        )
        .withEndpointConfiguration(
          new EndpointConfiguration("http://localhost:9324", "elasticmq")
        )
        .build()
    client.createQueue(testQueueName)

    lazy val producer =
      SqsProducer
        .resource[IO](testQueueName, Session.AUTO_ACKNOWLEDGE, client)
  }

  "SqsProducer" should "produce single text message" in new Fixture {
    producer
      .use(_.single[Event, String, TextMessage](Event(1, "test")))
      .unsafeRunSync() shouldEqual {}
  }

  it should "produce single binary message" in new Fixture {
    producer
      .use(_.single[Event, Stream[IO, Byte], BytesMessage](Event(1, "test")))
      .unsafeRunSync() shouldEqual {}
  }

  it should "produce multiple text messages" in new Fixture {
    val events = (0 to 10).map(i => Event(i, "test")).toIterator
    producer
      .use(
        _.multiple[Event, String, TextMessage](
          Stream.fromIterator[IO, Event](events)
        ).compile.drain
      )
      .unsafeRunSync() shouldEqual {}
  }

  it should "produce multiple binary messages" in new Fixture {
    val events = (0 to 10).map(i => Event(i, "test")).toIterator
    producer
      .use(
        _.multiple[Event, Stream[IO, Byte], BytesMessage](
          Stream.fromIterator[IO, Event](events)
        ).compile.drain
      )
      .unsafeRunSync() shouldEqual {}
  }
}
