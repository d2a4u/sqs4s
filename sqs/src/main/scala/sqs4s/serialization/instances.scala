package sqs4s.serialization

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.amazon.sqs.javamessaging.message.{SQSBytesMessage, SQSTextMessage}
import fs2.{Chunk, Stream}
import javax.jms.{BytesMessage, TextMessage}

trait decoders extends scala.AnyRef {
  implicit def strMsg[F[_]: Monad, T](
    implicit deserializer: MessageDeserializer[F, String, T]
  ) =
    new MessageDecoder[F, TextMessage, String, T] {
      override def from(msg: TextMessage): F[String] = msg.getText.pure[F]
      override def deserialize(u: String): F[T] = deserializer.deserialize(u)
    }

  implicit def bytesMsg[F[_]: Sync, T](
    implicit deserializer: MessageDeserializer[F, Stream[F, Byte], T]
  ) = {
    new MessageDecoder[F, BytesMessage, Stream[F, Byte], T] {
      override def from(msg: BytesMessage): F[Stream[F, Byte]] = {
        val bodyLength = msg.getBodyLength
        val blockSize = 1024
        var read = 0L
        Stream
          .fromIterator[F, Array[Byte]](new Iterator[Array[Byte]] {
            override def hasNext: Boolean = bodyLength > read

            override def next(): Array[Byte] = {
              val readNextSize =
                if (bodyLength < blockSize) bodyLength.toInt
                else if ((bodyLength - read) > blockSize) blockSize.toInt
                else (bodyLength - read).toInt
              val bytes = Array.ofDim[Byte](readNextSize)
              msg.readBytes(bytes)
              read = read + readNextSize
              bytes
            }
          })
          .flatMap(arr => Stream.chunk[F, Byte](Chunk.bytes(arr)))
          .pure[F]
      }

      override def deserialize(u: Stream[F, Byte]): F[T] =
        deserializer.deserialize(u)
    }
  }
}

trait encoders extends scala.AnyRef {
  implicit def strMsg[F[_]: Monad, T](
    implicit serializer: MessageSerializer[F, T, String]
  ) =
    new MessageEncoder[F, T, String, TextMessage] {
      override def to(str: String): F[TextMessage] =
        new SQSTextMessage(str)
          .pure[F]
          .widen[TextMessage]

      override def serialize(t: T): F[String] = serializer.serialize(t)
    }

  implicit def binMsg[F[_]: Sync, T](
    implicit serializer: MessageSerializer[F, T, Stream[F, Byte]]
  ) = {
    new MessageEncoder[F, T, Stream[F, Byte], BytesMessage] {
      override def to(stream: Stream[F, Byte]): F[BytesMessage] = {
        stream.compile
          .foldChunks(new SQSBytesMessage()) {
            case (msg, bytes) =>
              msg.writeBytes(bytes.toArray)
              msg
          }
          .widen[BytesMessage]
      }

      override def serialize(t: T): F[Stream[F, Byte]] =
        serializer.serialize(t)
    }
  }
}

trait instances extends decoders with encoders {}

object decoders extends decoders

object encoders extends encoders

object instances extends instances
