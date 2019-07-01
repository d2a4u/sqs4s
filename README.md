# queue4s

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Build Status](https://travis-ci.com/d2a4u/sqs4s.svg?branch=master)](https://travis-ci.com/d2a4u/sqs4s)
[![codecov](https://codecov.io/gh/d2a4u/sqs4s/branch/master/graph/badge.svg)](https://codecov.io/gh/d2a4u/sqs4s)
[![Download](https://api.bintray.com/packages/d2a4u/sqs4s/sqs4s-core/images/download.svg)](https://bintray.com/d2a4u/sqs4s/sqs4s-core/_latestVersion)

Streaming client for AWS SQS using fs2

## Install

The latest version is 0.1.x. See the badge at the top of the README for the exact version number.

Add the following to your `build.sbt`:

```scala
resolvers += Resolver.bintrayRepo("d2a4u", "sqs4s")

// available for Scala 2.12
libraryDependencies += "io.queue4s" %% "queue4s-core" % "0.1.0"
libraryDependencies += "io.queue4s" %% "queue4s-sqs" % "0.1.0"
```

## Usage

### Producing

#### Encoder & Serializer

Any case classes can be transformed into a SQS message by implementing the 
following encoder and serializer:

```scala
abstract class MessageEncoder[F[_]: Monad, T, U, M] {
  def to(u: U): F[M]
  def serialize(t: T): F[U]
  def encode(t: T): F[M] = serialize(t).flatMap(to)
}

abstract class MessageSerializer[F[_], T, U] {
  def serialize(t: T): F[U]
}
```

Where `T` is the type of the case class, `U` is the intermediate data type and
`M` is the SQS message type. There are 2 built-in encoders from `String` and
`Stream[F, Byte]` such as `MessageEncoder[F[_]: Monad, T, String, TextMessage]`.
So as long as there is an implicit `MessageSerializer` in scope, a SQS message
can be created. Here is a simple example for JSON using `circe`:

```MessageSerializer.instance[IO, Event, String](_.asJson.noSpaces.pure[IO])```

#### Producer

To create a `cats.effect.Resource` of `SqsProducer`:

```scala
SqsProducer.resource[IO]("test-queue", Session.AUTO_ACKNOWLEDGE, client)
```

And to publish a message:

```scala 
producerSrc
      .use(_.single[Event, String, TextMessage](Event(1, "test")))
      .unsafeRunSync()
```
### Consuming

#### Decoder & Deserializer

Reversely, a SQS message can be decoded and deserialized by having instances of:

```scala
abstract class MessageDecoder[F[_]: Monad, M, U, T] {
  def from(msg: M): F[U]
  def deserialize(u: U): F[T]
  def decode(msg: M): F[T] = from(msg).flatMap(deserialize)
}

abstract class MessageDeserializer[F[_], U, T] {
  def deserialize(u: U): F[T]
}
```
Again, a trivial JSON implementation can be:
```scala
implicit val deserializerStr: MessageDeserializer[IO, String, Event] =
    MessageDeserializer.instance[IO, String, Event](
      str => IO.fromEither(decode[Event](str))
    )
``` 

#### Consumer

To create a `cats.effect.Resource` of `SqsConsumer`:

```scala
SqsConsumer.resource[IO]("test-queue", Session.AUTO_ACKNOWLEDGE, client)
```

And to start consuming messages as stream:

```scala 
consumerSrc.use(_.consume().flatMap(_.take(1).compile.toList))
```

## License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT)