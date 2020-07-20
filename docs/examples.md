---
id: examples
title: Examples
sidebar_label: Examples
---

## Create Queue

```scala
val created = BlazeClientBuilder[IO](ec).resource
  .use { client =>
    CreateQueue[IO]("test", sqsEndpoint).runWith(client, setting)
  }
  .unsafeRunSync()
```

## Produce and Consume

```scala
BlazeClientBuilder[IO](ec)
  .withMaxTotalConnections(128)
  .withMaxWaitQueueLimit(2048)
  .withMaxConnectionsPerRequestKey(Function.const(2048))
  .resource
  .use { client =>
    val producer = SqsProducer[String](client, settings)
    val consumer = SqsConsumer[String](client, consumerSettings)
    // mapAsync number should match connection pool connections
    Stream.emits[IO, String](List.fill(10)("Test"))
      .mapAsync(128)(producer.produce)
      .compile
      .drain >> consumer.dequeueAsync(128).take(10).compile.drain
  }.unsafeRunSync()
```

## Use Credential resource

```scala
val clientSrc = BlazeClientBuilder[IO](ec)
  .withMaxTotalConnections(256)
  .withMaxWaitQueueLimit(2048)
  .withMaxConnectionsPerRequestKey(Function.const(2048))
  .resource

val consumed = for {
  client <- Stream.resource(clientSrc)
  cred <- Stream.resource(Credential.instanceMetadataResource(
    client,
    6.hours,
    5.minutes
  ))
  producer = SqsProducer[TestMessage](
    client,
    SqsConfig(queue, cred, region)
  )
  consumer = SqsConsumer[TestMessage](
    client,
    ConsumerConfig(
      queue = queue,
      credential = cred,
      region = region,
      waitTimeSeconds = Some(1),
      pollingRate = 2.seconds
    )
  )
  _ <- producer.batchProduce(input, _.int.toString.pure[IO])
  result <- consumer.dequeueAsync(256)
} yield result
```

## Pub/Sub

```scala
import java.time.Instant

import cats.implicits._
import cats.effect.{ExitCode, IO, IOApp, Sync}
import org.http4s.Uri
import sqs4s.api.{AwsAuth, ConsumerSettings, SqsSettings}
import sqs4s.serialization.{SqsDeserializer, SqsSerializer}
import fs2._
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api.hi.{SqsConsumer, SqsProducer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object Main extends IOApp {

  case class Event(
    ts: Long
  )

  object Event {
    implicit def deserializer[F[_]: Sync]: SqsDeserializer[F, Event] = new SqsDeserializer[F, Event] {
      override def deserialize(s: String): F[Event] = Event(s.toLong).pure[F]
    }

    implicit val serializer: SqsSerializer[Event] = (t: Event) => t.ts.toString
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val accessKey = sys.env("ACCESS_KEY")
    val secretKey = sys.env("SECRET_KEY")
    val awsAccountId = sys.env("AWS_ACCOUNT_ID")
    val queue = Uri.unsafeFromString(s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test")
    val settings = SqsSettings(queue, AwsAuth(accessKey, secretKey, "eu-west-1"))
    val consumerSettings = ConsumerSettings(
      queue = settings.queue,
      auth = settings.auth,
      waitTimeSeconds = Some(1),
      pollingRate = 2.seconds
    )

    val clientResource = BlazeClientBuilder[IO](ExecutionContext.global)
      .withMaxTotalConnections(256)
      .withMaxWaitQueueLimit(2048)
      .withMaxConnectionsPerRequestKey(Function.const(2048))
      .resource

    val producerResource = clientResource.map { client =>
      SqsProducer[Event](client, settings)
    }

    val producingStream = Stream.resource(producerResource).flatMap { producer =>
      Stream
        .repeatEval(IO(Instant.now().toEpochMilli))
        .metered(1.second)
        .map(Event.apply)
        .evalMap(e => producer.produce(e) >> IO(println("++ Produced: " + e)))
    }

    val consumerResource = clientResource.map { client =>
      SqsConsumer[Event](client, consumerSettings)
    }

    val consumingStream = Stream.resource(consumerResource).flatMap { consumer =>
      consumer.consumeAsync(128) { consumed =>
        IO(println("-- Consumed: " + consumed))
      }
    }

    Stream(
      producingStream,
      consumingStream
    ).parJoinUnbounded.compile.drain.as(ExitCode.Success)
  }
}
```
