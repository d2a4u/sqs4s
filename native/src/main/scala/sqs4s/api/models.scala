package sqs4s.api

import org.http4s.Uri
import sqs4s.auth.CredProvider

import scala.concurrent.duration._

@deprecated("use SqsConfig instead", "1.1.0")
case class SqsSettings(queue: Uri, auth: AwsAuth)

/**
  * Settings for SQS consumer
  * @param maxRead maximum number of messages to receive per request, max is 10
  * @param visibilityTimeout a timeout in seconds to prevent other consumers from processing the message again
  * @param waitTimeSeconds an option wait time for long polling, the request is blocked during this time
  * @param pollingRate an polling interval, this value should be slightly higher than [[waitTimeSeconds]]
  * @param initialDelay when request to SQS fails, it will be retried internally with an initial delay
  * @param maxRetry when request to SQS fails, it will be retried internally [[maxRetry]] times
  */
@deprecated("use ConsumerConfig instead", "1.1.0")
case class ConsumerSettings(
  queue: Uri,
  auth: AwsAuth,
  maxRead: Int = 10,
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None,
  pollingRate: FiniteDuration = 100.millis,
  initialDelay: FiniteDuration = 100.millis,
  maxRetry: Int = 10
)

@deprecated("use CredProvider instead", "1.1.0")
case class AwsAuth(accessKey: String, secretKey: String, region: String)

case class SqsConfig[F[_]](
  queue: Uri,
  credProvider: CredProvider[F],
  region: String
)

case class ConsumerConfig[F[_]](
  queue: Uri,
  credProvider: CredProvider[F],
  region: String,
  maxRead: Int = 10,
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None,
  pollingRate: FiniteDuration = 100.millis,
  initialDelay: FiniteDuration = 100.millis,
  maxRetry: Int = 10
)
