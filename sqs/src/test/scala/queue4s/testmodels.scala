package queue4s

import java.io._

import cats.effect.{IO, Sync}
import cats.implicits._
import fs2._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import javax.jms.TextMessage
import queue4s.serialization._
import queue4s.serialization.instances._

@SerialVersionUID(100L)
case class Event(
  id: Int,
  name: String)
    extends Serializable

object Event {
  implicit val serializerStr: MessageSerializer[IO, Event, String] =
    MessageSerializer.instance[IO, Event, String](_.asJson.noSpaces.pure[IO])
  implicit val deserializerStr: MessageDeserializer[IO, String, Event] =
    MessageDeserializer.instance[IO, String, Event](
      str => IO.fromEither(decode[Event](str))
    )
  implicit val serializerBin: MessageSerializer[
    IO,
    Event,
    fs2.Stream[IO, Byte]
  ] =
    MessageSerializer.instance[IO, Event, fs2.Stream[IO, Byte]] { event =>
      val bytesF = Sync[IO].delay {
        val stream = new ByteArrayOutputStream()
        val oos = new ObjectOutputStream(stream)
        oos.writeObject(event)
        oos.close
        stream.toByteArray()
      }
      bytesF.map { bytes =>
        Stream.chunk(Chunk.bytes(bytes))
      }
    }
  implicit val deserializerBin: MessageDeserializer[
    IO,
    fs2.Stream[IO, Byte],
    Event
  ] =
    MessageDeserializer.instance[IO, Stream[IO, Byte], Event] { bytes =>
      bytes.chunks.compile.toList.map { chunks =>
        val data = new ByteArrayInputStream(chunks.flatMap(_.toList).toArray)
        val ois = new ObjectInputStream(data)
        val event = ois.readObject().asInstanceOf[Event]
        ois.close
        event
      }
    }

  implicit val encoder: MessageEncoder[IO, Event, String, TextMessage] =
    strMsg[IO, Event]
  implicit val decoder: MessageDecoder[IO, TextMessage, String, Event] =
    strMsg[IO, Event]
}
