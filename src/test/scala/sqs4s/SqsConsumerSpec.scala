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

class SqsConsumerSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  var server: SQSRestServer = _
  val accessKey = "x"
  val secretKey = "x"
  val txtQueueName = "test-queue-txt"
  val binQueueName = "test-queue-bin"

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
  }

  "SqsConsumer" should "consume text message" in new Fixture {
    client.createQueue(txtQueueName)
    val event = Event(1, "test")
    val producerStrSrc =
      SqsProducer
        .resource[IO](txtQueueName, Session.AUTO_ACKNOWLEDGE, client)
    val consumerStrSrc =
      SqsConsumer.resourceStr[IO, Event](
        txtQueueName,
        Session.AUTO_ACKNOWLEDGE,
        20,
        client
      )
    val consumed = for {
      _ <- producerStrSrc.use(_.single[Event, String, TextMessage](event))
      events <- consumerStrSrc.use(_.consume().take(1).compile.toList)
    } yield events
    consumed.unsafeRunSync() contains theSameElementsAs(List(event))
  }

  it should "consume binary message" in new Fixture {
    client.createQueue(binQueueName)
    val event = Event(1, "test")
    val producerBinSrc =
      SqsProducer
        .resource[IO](binQueueName, Session.AUTO_ACKNOWLEDGE, client)

    val consumerBinSrc =
      SqsConsumer.resourceBin[IO, Event](
        binQueueName,
        Session.AUTO_ACKNOWLEDGE,
        20,
        client
      )
    val consumed = for {
      _ <- producerBinSrc.use(
        _.single[Event, Stream[IO, Byte], BytesMessage](event)
      )
      events <- consumerBinSrc.use(_.consume().take(1).compile.toList)
    } yield events
    consumed.unsafeRunSync() contains theSameElementsAs(List(event))
  }

  it should "manually acknowledge message" in new Fixture {
    client.createQueue(txtQueueName)
    val events = Stream.fromIterator[IO, Event](
      (0 to 9).map(i => Event(i, "test")).toIterator
    )
    val producerStrSrc =
      SqsProducer
        .resource[IO](txtQueueName, Session.AUTO_ACKNOWLEDGE, client)
    val consumerStrSrc =
      SqsConsumer.resourceStr[IO, Event](
        txtQueueName,
        Session.CLIENT_ACKNOWLEDGE,
        20,
        client
      )
    val consumed = for {
      _ <- producerStrSrc.use(
        _.multiple[Event, String, TextMessage](events).compile.drain
      )
      acked <- consumerStrSrc.use { consumer =>
        consumer
          .receive()
          .map(_.original)
          .through(consumer.ack())
          .take(10)
          .compile
          .drain
      }
    } yield acked

    consumed.unsafeRunSync() shouldEqual {}
  }
}
