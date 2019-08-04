package sqs4s.internal.util

import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect._
import org.http4s.Method

class CanonicalSpec extends IOSpec {

  import canonical._
  import common._

  val queries =
    List("Action" -> "ListUsers", "Version" -> "2010-05-08")

  val headers =
    List(
      "content-type" -> "application/x-www-form-urlencoded; charset=utf-8",
      "host" -> "iam.amazonaws.com"
    )

  val canonicalReq: String =
    s"""GET
       |/
       |Action=ListUsers&Version=2010-05-08
       |content-type:application/x-www-form-urlencoded; charset=utf-8
       |host:iam.amazonaws.com
       |x-amz-date:$fakeTimeStamp
       |
       |content-type;host;x-amz-date
       |e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""".stripMargin

  "Signer" should "generates canonical request" in {
    val req = for {
      millis <- Clock[IO].realTime(TimeUnit.MILLISECONDS)
      zoneId <- IO.delay(ZoneId.systemDefault())
      now <- IO.delay(Instant.ofEpochMilli(millis).atZone(zoneId))
      sts <- canonicalRequest[IO](Method.GET, "/", queries, headers, None, now)
    } yield sts

    req.unsafeRunSync() shouldEqual canonicalReq
  }

  it should "generate SHA-256 hex digest correctly" in {
    sha256HexDigest[IO](canonicalReq)
      .unsafeRunSync() shouldEqual "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59"
  }
}
