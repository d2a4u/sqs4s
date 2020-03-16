package sqs4s.internal.aws4

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}

import cats.effect._
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.TimeUnit

trait IOSpec extends FlatSpecLike with Matchers {

  val testTimeStamp = "20150830T123600Z"
  val testDateTime =
    LocalDateTime
      .parse(testTimeStamp, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val ec: ExecutionContext = global
  implicit lazy val testClock = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO {
      testDateTime
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()
    }
    def monotonic(unit: TimeUnit): IO[Long] = ???
  }
}
