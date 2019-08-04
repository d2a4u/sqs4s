package sqs4s.internal.util

import java.time.{LocalDateTime, ZoneId}

import cats.effect._
import org.scalatest.{FlatSpecLike, Matchers}
import sqs4s.internal.util.common.DateTimeFormat

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.TimeUnit

trait IOSpec extends FlatSpecLike with Matchers {

  val fakeTimeStamp = "20150830T123600Z"
  implicit val timer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val testClock = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO {
      LocalDateTime
        .parse(fakeTimeStamp, DateTimeFormat)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    }
    def monotonic(unit: TimeUnit): IO[Long] = ???
  }
}
