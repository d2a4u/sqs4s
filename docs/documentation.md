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

##### produce 

Produce a message to SQS

##### batchProduce

Produce messages to SQS in batch operation

##### consume

Consume messages from SQS as a fs2 Stream, only acknowledge the message only 
when it has been processed

##### consumeAsync

Consume messages but making multiple calls to SQS in parallel

##### dequeue
 
Get messages from SQS as a fs2 Stream but acknowledge right away

##### dequeueAsync

Get messages but making multiple calls to SQS in parallel. This takes a `process`
function of `T => F[Unit]` to represent processing a message in a queue. Please 
note that this does not work for FIFO queue because internally, the client pulls
multiple messages from SQS in parallel, run the `process` process function on 
each message and finally, acknowledge messages in batch.

##### peek

Peek for X number of messages in SQS without acknowledging them, as a result,
the messages stay in the queue.

