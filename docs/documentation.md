---
id: overview
title: Overview
sidebar_label: Overview
---

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
- DeleteMessageBatch
- ReceiveMessage
- SendMessage
- SendMessageBatch

#### High Level

- `produce` produce a message to SQS
- `batchProduce` produce messages to SQS in batch operation
- `consume` consume messages from SQS as a fs2 Stream, only acknowledge the message only when it has been processed
- `consumeAsync` consume messages but making multiple calls to SQS in parallel
- `dequeue` get messages from SQS as a fs2 Stream but acknowledge right away
- `dequeueAsync` get messages but making multiple calls to SQS in parallel
- `peek` peek for X number of messages in SQS without acknowledging them

