package benchmark

import cats.effect.{ContextShift, IO, Resource, Timer}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.sqs.{AmazonSQSAsync, AmazonSQSAsyncClientBuilder}
import com.danielasfregola.randomdatagenerator.RandomDataGenerator._
import javax.jms.{Session, TextMessage}
import org.elasticmq.rest.sqs.{SQSRestServer, SQSRestServerBuilder}
import org.openjdk.jmh.annotations._
import sqs4s.{Event, SqsProducer}

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

@State(Scope.Benchmark)
class ProducerBenchmark {

  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)

  private var server: SQSRestServer = _
  val accessKey = "x"
  val secretKey = "x"
  val testQueueName = "producer-bm-queue"

  def setup(): Resource[IO, SqsProducer[IO]] = {
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
    SqsProducer
      .resource[IO](testQueueName, Session.AUTO_ACKNOWLEDGE, client)
  }

  @Setup
  def prepare(): Unit =
    server = SQSRestServerBuilder.start()

  @TearDown
  def shutdown(): Unit = {
    server.stopAndWait()
    ()
  }

  @Param(Array("1", "10", "100", "1000"))
  private var numberOfEvents: Int = 0

  def batchSize(numberOfEvents: Int): Int =
    numberOfEvents match {
      case 1 => 1
      case 10 => 10
      case _ => 20
    }

  @Benchmark
  def multiple(): Unit = {
    setup()
      .use(
        _.multiple[Event, String, TextMessage](
          fs2.Stream
            .emits[IO, Event](random[Event](numberOfEvents))
        ).compile.drain
      )
      .unsafeRunSync()
  }

  @Benchmark
  def batch(): Unit = {
    setup()
      .use(
        _.batch[Event, String, TextMessage](
          fs2.Stream.emits[IO, (String, Event)](
            random[(String, Event)](numberOfEvents)
          ),
          batchSize(numberOfEvents),
          5.seconds
        ).compile.drain
      )
      .unsafeRunSync()
  }
}
