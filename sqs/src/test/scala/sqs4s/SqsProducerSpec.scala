package sqs4s

import cats.effect._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.sqs.{AmazonSQSAsync, AmazonSQSAsyncClientBuilder}
import fs2._
import javax.jms.{BytesMessage, Session, TextMessage}
import org.elasticmq.rest.sqs.{SQSRestServer, SQSRestServerBuilder}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Inspectors, Matchers}
import sqs4s.serialization.instances._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

class SqsProducerSpec
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with Inspectors {

  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)

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
    val events = (0 to 9).map(i => Event(i, "test")).toIterator
    producer
      .use(
        _.multiple[Event, String, TextMessage](
          Stream.fromIterator[IO, Event](events)
        ).compile.toList
      )
      .unsafeRunSync()
      .length shouldEqual 10
  }

  it should "produce multiple binary messages" in new Fixture {
    val events = (0 to 9).map(i => Event(i, "test")).toIterator
    producer
      .use(
        _.multiple[Event, Stream[IO, Byte], BytesMessage](
          Stream.fromIterator[IO, Event](events)
        ).compile.toList
      )
      .unsafeRunSync()
      .length shouldEqual 10
  }

  it should "send messages in batch" in new Fixture {
    val events = (0 to 9).map(i => (i.toString, Event(i, "test"))).toList
    val results = producer
      .use(
        _.batch[Event, String, TextMessage](
          Stream.fromIterator[IO, (String, Event)](events.toIterator),
          3,
          5.seconds
        ).compile.toList
      )
      .unsafeRunSync()
    val expect = (0 to 9).map(_.toString)
    val sentIds = results.flatMap(_.getSuccessful.asScala.map(_.getId))
    sentIds should contain theSameElementsAs expect
  }
}
