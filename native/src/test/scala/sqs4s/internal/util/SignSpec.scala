package sqs4s.internal.util

import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}

class SignSpec extends IOSpec {

  import signature._
  import common._

  val canonicalReq =
    "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59"
  val expectStringToSign =
    """AWS4-HMAC-SHA256
      |20150830T123600Z
      |20150830/us-east-1/iam/aws4_request
      |f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59""".stripMargin
  val expectSigningKey =
    "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
  val expectSignature =
    "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7"
  val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
  val region = "us-east-1"
  val service = "iam"

  val fakeNow = (for {
    millis <- Clock[IO].realTime(TimeUnit.MILLISECONDS)
    zoneId <- IO.delay(ZoneId.systemDefault())
    now <- IO.delay(Instant.ofEpochMilli(millis).atZone(zoneId))
  } yield now).unsafeRunSync()

  "String to sign" should "be generated correctly" in {
    stringToSign[IO](region, service, canonicalReq, fakeNow)
      .unsafeRunSync() shouldEqual expectStringToSign
  }

  "Signature" should "be generated correctly" in {
    signingKey[IO](secretKey, region, service, fakeNow)
      .flatMap(hexDigest[IO])
      .unsafeRunSync() shouldEqual expectSigningKey
  }

  "Calling sign" should "calculate signature data correctly" in {
    sign[IO](secretKey, region, service, canonicalReq)
      .unsafeRunSync() shouldEqual expectSignature
  }
}
