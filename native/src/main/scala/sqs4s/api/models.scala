package sqs4s.api

import org.http4s.Uri
import sqs4s.auth.Credentials

import scala.concurrent.duration._

final case class SqsConfig[F[_]](
  queue: Uri,
  credentials: Credentials[F],
  region: String
)

final case class ConsumerConfig[F[_]](
  queue: Uri,
  credentials: Credentials[F],
  region: String,
  maxRead: Int = 10,
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None,
  pollingRate: FiniteDuration = 100.millis,
  initialDelay: FiniteDuration = 100.millis,
  maxRetry: Int = 10
)
