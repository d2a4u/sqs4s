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
  cred <- Stream.resource(Credentials.chain(client))
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

## More

More examples can be found in `ClientItSpec.scala`
