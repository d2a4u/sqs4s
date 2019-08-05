package sqs4s.internal

import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}
import org.http4s._
import sqs4s.internal.util.IOSpec

import scala.io.Source

class Aws4TestSuiteCanonicalRequestSpec extends IOSpec {

  import util.canonical._

  val ts =
    Instant
      .ofEpochMilli(
        Clock[IO].realTime(TimeUnit.MILLISECONDS).unsafeRunSync()
      )
      .atZone(ZoneId.systemDefault())

  def expect(testName: String): String =
    Source
      .fromResource(s"aws-sig-v4-test-suite/$testName/$testName.creq")
      .getLines()
      .mkString("\n")

  "get header key duplicate" should "be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.fromString("/").right.get,
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("My-Header1", "value2"),
        Header("My-Header1", "value2"),
        Header("My-Header1", "value1")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-header-key-duplicate"
    )
  }

  "get header value multiline" should "be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.fromString("/").right.get,
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("My-Header1", """value1
            |  value2
            |     value3
          """.stripMargin)
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-header-value-multiline"
    )
  }

  "get unreserved" should "be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri
        .fromString(
          "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        )
        .right
        .get,
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-unreserved"
    )
  }

  "get utf8" should "be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = canonicalUri[IO]("/ሴ").unsafeRunSync(),
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-utf8"
    )
  }

  "get vanilla" should "be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.fromString("/").right.get,
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla"
    )
  }
}
