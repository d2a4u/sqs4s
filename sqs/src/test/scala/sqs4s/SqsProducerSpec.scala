package sqs4s

import cats.effect._
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.sqs.{AmazonSQSAsync, AmazonSQSAsyncClientBuilder}
import com.danielasfregola.randomdatagenerator.RandomDataGenerator._
import fs2._
import javax.jms.{BytesMessage, Session, TextMessage}
import org.elasticmq.rest.sqs.{SQSRestServer, SQSRestServerBuilder}
import org.scalatest.{BeforeAndAfterAll, Inspectors}
import sqs4s.serialization.instances._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqsProducerSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with Inspectors {

  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  private var server: SQSRestServer = _
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
      .use(_.single[Event, String, TextMessage](random[Event]))
      .unsafeRunSync() shouldEqual {}
  }

  it should "produce single binary message" in new Fixture {
    producer
      .use(_.single[Event, Stream[IO, Byte], BytesMessage](random[Event]))
      .unsafeRunSync() shouldEqual {}
  }

  it should "produce multiple text messages" in new Fixture {
    val events = random[Event](10).toIterator
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
    val events = random[Event](10).toIterator
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
    val events = random[Event](10).map(event => (event.id, event))
    val results = producer
      .use(
        _.batch[Event, String, TextMessage](
          Stream.fromIterator[IO, (String, Event)](events.toIterator),
          3,
          5.seconds
        ).compile.toList
      )
      .unsafeRunSync()
    val sentIds = results.flatMap(_.getSuccessful.asScala.map(_.getId))
    sentIds should contain theSameElementsAs events.map(_._1)
  }

  it should "attempt to send messages in batch" in new Fixture {
    val events = random[Event](10).map(event => (event.id, event))
    val results = producer
      .use(
        _.attemptBatch[Event, String, TextMessage](
          Stream.fromIterator[IO, (String, Event)](events.toIterator),
          3,
          5.seconds
        ).compile.toList
      )
      .unsafeRunSync()
    val sentIds =
      results.collect {
        case Right(result) =>
          result.getSuccessful.asScala.toList.map(_.getId)
      }.flatten
    val expectIds = events.map {
      case (id, _) => id
    }
    sentIds should contain theSameElementsAs expectIds
  }

  it should "capture error to Left when attempt sending message in batch" in new Fixture {
    // when sending batch, each entry should have unique ID,
    // here, all events have the same ID, it should fail
    val event = random[Event]
    val erroneous = Stream.fromIterator[IO, (String, Event)](
      List.fill(10)(event).map(e => (e.id, e)).toIterator
    )
    val results = producer
      .use(
        _.attemptBatch[Event, String, TextMessage](erroneous, 2, 5.seconds).compile.toList
      )
      .unsafeRunSync()
    forAll(results) {
      case Left(_) => succeed
      case Right(_) => fail()
    }
  }
}
