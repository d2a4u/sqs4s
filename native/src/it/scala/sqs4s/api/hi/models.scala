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

  implicit val desrlz =
    SqsDeserializer.instance { u =>
      IO.fromEither(parser.decode[TestMessage](u))
    }

  implicit val srlz =
    SqsSerializer.instance[TestMessage](_.asJson.noSpaces)

  implicit val arbTestMessage: Arbitrary[TestMessage] = {
    val gen = for {
      str <- Gen.alphaNumStr
      int <- Gen.choose(Int.MinValue, Int.MaxValue)
      bool <- Gen.oneOf(Seq(true, false))
    } yield TestMessage(str, int, bool)
    Arbitrary(gen)
  }

  def sample: TestMessage = arbTestMessage.arbitrary.sample.get

  def arb(n: Int): List[TestMessage] = List.fill(n)(sample)
}
