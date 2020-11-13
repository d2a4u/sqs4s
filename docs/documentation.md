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

*NOTE:* All `xxxAsync` APIs are not suitable for FIFO queue because internally, 
the client pulls multiple messages from SQS in parallel, hence, the ordering might
be lost.

##### produce 

Produce a message to SQS

##### batchProduce

Produce messages to SQS in batch

##### consume

Read messages from SQS queue as T, for each message, apply process `process` 
function and automatically acknowledge the message on completion of `process`.

Suitable for FIFO and non-FIFO queue. The message is acknowledged one by one 
instead of batching.

##### consumeAsync

Parallel read messages from SQS queue as a Stream of T, for each message,
apply process `process` function and automatically acknowledge the
messages in batch.

##### dequeue
 
Read messages from SQS queue as a Stream of T, automatically acknowledge
messages **before** pushing T down the Stream.

Suitable for FIFO and non-FIFO queue. The message is acknowledged one by
one instead of batching.

##### dequeueAsync

Parallel read messages from SQS queue as a Stream of T, automatically
acknowledge messages BEFORE pushing T into the Stream.

##### peek

Read N messages from the queue without acknowledge them.

##### read

Similar to `peek` but return also a ReceiptHandle to manually acknowledge
messages and return up to `maxRead` number of messages.

##### reads

Similar to `read` but return a Stream of `ReceiveMessage.Result[T]` which
can be used to manually acknowledge messages.

##### readsAsync

Similar to `reads` but read messages in parallel.

##### ack 

Acknowledge that a message has been read one by one.

##### batchAck

Batch acknowledge that messages has been read.
