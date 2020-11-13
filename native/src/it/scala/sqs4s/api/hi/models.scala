package sqs4s.api.hi

import cats.effect.IO
import fs2.Stream
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{parser, _}
import org.scalacheck.{Arbitrary, Gen}
import sqs4s.serialization.{SqsDeserializer, SqsSerializer}

case class TestMessage(string: String, int: Int, boolean: Boolean)

object TestMessage {
  implicit val encode: Encoder[TestMessage] = deriveEncoder
  implicit val decode: Decoder[TestMessage] = deriveDecoder

  implicit val desrlz = new SqsDeserializer[IO, TestMessage] {
    override def deserialize(u: String): IO[TestMessage] =
      IO.fromEither(parser.decode[TestMessage](u))
  }

  implicit val srlz = new SqsSerializer[TestMessage] {
    override def serialize(t: TestMessage): String =
      t.asJson.noSpaces
  }

  implicit val arbTestMessage: Arbitrary[TestMessage] = {
    val gen = for {
      str <- Gen.alphaNumStr
      int <- Gen.choose(Int.MinValue, Int.MaxValue)
      bool <- Gen.oneOf(Seq(true, false))
    } yield TestMessage(str, int, bool)
    Arbitrary(gen)
  }

  def arbStream(n: Long): Stream[IO, TestMessage] = {
    val msg = arbTestMessage.arbitrary.sample.get
    Stream
      .random[IO]
      .map(i => msg.copy(int = i))
      .take(n)
  }
}
