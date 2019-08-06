import java.time.{LocalDateTime, ZoneId}

import cats.effect._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.TimeUnit
import sqs4s.internal.util.canonical._

import java.time.format.DateTimeFormatter

val fakeTimeStamp = "20150830T123600Z"
implicit val timer: Timer[IO] = IO.timer(global)
implicit val cs: ContextShift[IO] = IO.contextShift(global)
implicit val ec: ExecutionContext = global
val DateTimeFormat =
  DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
implicit lazy val testClock = new Clock[IO] {
  def realTime(unit: TimeUnit): IO[Long] = IO {
    LocalDateTime
      .parse(fakeTimeStamp, DateTimeFormat)
      .atZone(ZoneId.systemDefault())
      .toInstant()
      .toEpochMilli()
  }
  def monotonic(unit: TimeUnit): IO[Long] = ???
}

val uri = canonicalUri[IO](
  "/?-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz=-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
).unsafeRunSync()

uri.query