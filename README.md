# sqs4s

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Build Status](https://travis-ci.com/d2a4u/sqs4s.svg?branch=master)](https://travis-ci.com/d2a4u/sqs4s)
[![codecov](https://codecov.io/gh/d2a4u/sqs4s/branch/master/graph/badge.svg)](https://codecov.io/gh/d2a4u/sqs4s)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/8a331de033cb4700acddb175af4148bb)](https://www.codacy.com/app/d2a4u/sqs4s?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=d2a4u/sqs4s&amp;utm_campaign=Badge_Grade)
[![Download](https://api.bintray.com/packages/d2a4u/sqs4s/sqs4s-native/images/download.svg)](https://bintray.com/d2a4u/sqs4s/sqs4s-native/_latestVersion)

Streaming client for AWS SQS using [fs2](https://github.com/functional-streams-for-scala/fs2).

The library does not have dependency on Java AWS SDK, it provides features to consume
and produce messages by leveraging AWS SQS's HTTP API. Hence, internally, consuming
and producing to SQS are just pure HTTP calls. Authentication implements 
the [AWS Signature V4](https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html).

## Install

See the badge at the top of the README for the exact version number.

Add the following to your `build.sbt`:

```scala
resolvers += Resolver.bintrayRepo("d2a4u", "sqs4s")

// available for Scala 2.12
libraryDependencies += "io.sqs4s" %% "sqs4s-native" % "LATEST_VERSION"
```

## Usage

*Limitation:* SQS HTTP API limits maximum message size of 256 KB and the message
body needs to be provided as URL's query parameter, hence: 

- we cannot make use of streaming message as request's body 
- the message should only has characters that are safe for URL encoding
- you should always use HTTPS

However, it is good practice to keep message's size small since it should only 
contains references rather than data.

### Serializing Messages

Because of the limitation stated above, serialize is actual quite simple:

```scala
def serialize(t: T): String
```
Just need to make sure that the serialized String only contains URL encoding
characters.

Deserialize is:

```scala
def deserialize(s: String): F[T]
```

Where `F` is `F[_]: MonadError[?[_], Throwable]` to encapsulate error.

### APIs

#### Low Level

1-2-1 implementation of [SQS' API](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_Operations.html)

- CreateQueue
- DeleteMessage
- ReceiveMessage
- SendMessage

#### High Level

- `produce`: produce a message to SQS
- `consume`: consume messages from SQS as a fs2 Stream, only acknowledge the message only when it has been processed
- `consumeAsync`: consume messages but making multiple calls to SQS in parallel
- `dequeue`: get messages from SQS as a fs2 Stream but acknowledge right away
- `dequeueAsync`: get messages but making multiple calls to SQS in parallel
- `peek`: peek for X number of messages in SQS without acknowledging them

## Examples

### Create Queue

```scala
val created = BlazeClientBuilder[IO](ec).resource
  .use { implicit client =>
    CreateQueue[IO]("test", sqsEndpoint).runWith(setting)
  }
  .unsafeRunSync()
```

### Produce and Consume

```scala
BlazeClientBuilder[IO](ec)
  .withMaxTotalConnections(128)
  .withMaxWaitQueueLimit(2048)
  .withMaxConnectionsPerRequestKey(Function.const(2048))
  .resource
  .use { implicit client =>
    val producer = SqsProducer.instance[IO, String](settings)
    val consumer = SqsConsumer.instance[IO, String](settings)
    // mapAsync number should match connection pool connections
    Stream.emits[IO, String](List.fill(10)("Test"))
      .mapAsync(128)(producer.produce)
      .compile
      .drain
      .flatMap(_ => consumer.dequeueAsync(128).take(10).compile.drain)
  }.unsafeRunSync()
```

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT)
