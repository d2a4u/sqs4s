package sqs4s.internal.aws4.aws4suite

import java.time.{Instant, ZoneId}
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}
import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import sqs4s.internal.aws4
import sqs4s.internal.aws4.IOSpec

import scala.io.Source

class CanonicalRequestSpec extends IOSpec {

  import aws4.canonical._

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

  def expectNested(groupName: String, testName: String): String =
    Source
      .fromResource(
        s"aws-sig-v4-test-suite/$groupName/$testName/$testName.creq"
      )
      .getLines()
      .mkString("\n")

  "get header key duplicate" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/",
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

  "get header value multiline" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/",
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

  "get unreserved" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri =
        uri"/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-unreserved"
    )
  }

  "get utf8" should "be transformed into canonical request correctly" in {
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

  "get vanilla" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla"
    )
  }

  "get vanilla empty query key" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/?Param1=value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-empty-query-key"
    )
  }

  "get vanilla query" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-query"
    )
  }

  "get vanilla query order key" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/?Param1=value2&Param1=Value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-query-order-key"
    )
  }

  "get vanilla query order key case" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/?Param2=value2&Param1=value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-query-order-key-case"
    )
  }

  "get vanilla query order value" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = uri"/?Param1=value2&Param1=value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-query-order-value"
    )
  }

  "get vanilla query unreserved" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = canonicalUri[IO](
        "/?-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz=-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
      ).unsafeRunSync(),
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-query-unreserved"
    )
  }

  "get vanilla utf8 query" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.GET,
      uri = canonicalUri[IO]("/?ሴ=bar").unsafeRunSync(),
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "get-vanilla-utf8-query"
    )
  }

  ignore should "get relative normalize path be correct" in {
    val req = Request[IO](
      method = Method.GET,
      uri = canonicalUri[IO]("/example/..").unsafeRunSync(),
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts)
      .unsafeRunSync() shouldEqual expectNested(
      "normalize-path",
      "get-relative"
    )
  }

  "post header key case" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-header-key-case"
    )
  }

  "post header key sort" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("My-Header1", "value1")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-header-key-sort"
    )
  }

  "post header value case" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("My-Header1", "VALUE1")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-header-value-case"
    )
  }

  "post sts token header after" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expectNested(
      "post-sts-token",
      "post-sts-header-after"
    )
  }

  "post sts token header before" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header(
          "X-Amz-Security-Token",
          "AQoDYXdzEPT//////////wEXAMPLEtc764bNrC9SAPBSM22wDOk4x4HIZ8j4FZTwdQWLWsKWHGBuFqwAeMicRXmxfpSPfIeoIYRqTflfKD8YUuwthAx7mSEI/qkPpKPi/kMcGdQrmGdeehM4IC1NtBmUpp2wUE8phUZampKsburEDy0KPkyQDYwT7WZ0wq5VSXDvp75YU9HFvlRd8Tx6q6fE8YQcHNVXAkiY9q6d+xo0rKwT38xVqr7ZD0u0iPPkUL64lIZbqBAz+scqKmlzm8FDrypNC9Yjc8fPOLn9FX9KSYvKTr4rvx3iSIlTJabIQwj2ICCR/oLxBA=="
        )
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expectNested(
      "post-sts-token",
      "post-sts-header-before"
    )
  }

  "post vanilla" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("X-Amz-Date", "20150830T123600Z")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-vanilla"
    )
  }

  "post vanilla empty query value" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/?Param1=value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("X-Amz-Date", "20150830T123600Z")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-vanilla-empty-query-value"
    )
  }

  "post vanilla query" should "be transformed into canonical request correctly" in {
    val req = Request[IO](
      method = Method.POST,
      uri = uri"/?Param1=value1",
      headers = Headers.of(
        Header("Host", "example.amazonaws.com"),
        Header("X-Amz-Date", "20150830T123600Z")
      )
    )

    canonicalRequest[IO](req, ts).unsafeRunSync() shouldEqual expect(
      "post-vanilla-query"
    )
  }

  "post x www form urlencoded" should "be transformed into canonical request correctly" in {
    val form = UrlForm("Param1" -> "value1")
    val req = POST(form, uri"/").map { post =>
      post.putHeaders(
        Header("Content-Type", "application/x-www-form-urlencoded"),
        Header("Host", "example.amazonaws.com"),
        Header("X-Amz-Date", "20150830T123600Z")
      )
    }

    (for {
      r <- req
      cr <- canonicalRequest[IO](r, ts)
    } yield cr).unsafeRunSync() shouldEqual
      """POST
        |/
        |
        |content-length:13
        |content-type:application/x-www-form-urlencoded
        |host:example.amazonaws.com
        |x-amz-date:20150830T123600Z
        |
        |content-length;content-type;host;x-amz-date
        |9095672bbd1f56dfc5b65f3e153adc8731a4a654192329106275f4c7b24d0b6e""".stripMargin
  }

  "post x www form urlencoded parameters" should "be transformed into canonical request correctly" in {
    val form = UrlForm("Param1" -> "value1")
    val req = POST(form, uri"/").map { post =>
      post.putHeaders(
        Header(
          "Content-Type",
          "application/x-www-form-urlencoded; charset=utf-8"
        ),
        Header("Host", "example.amazonaws.com"),
        Header("X-Amz-Date", "20150830T123600Z")
      )
    }

    (for {
      r <- req
      cr <- canonicalRequest[IO](r, ts)
    } yield cr).unsafeRunSync() shouldEqual
      """POST
        |/
        |
        |content-length:13
        |content-type:application/x-www-form-urlencoded; charset=utf-8
        |host:example.amazonaws.com
        |x-amz-date:20150830T123600Z
        |
        |content-length;content-type;host;x-amz-date
        |9095672bbd1f56dfc5b65f3e153adc8731a4a654192329106275f4c7b24d0b6e""".stripMargin
  }
}
