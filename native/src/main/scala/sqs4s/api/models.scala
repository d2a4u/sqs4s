package sqs4s.api

import org.http4s.Uri

import scala.concurrent.duration._

case class SqsSettings(queue: Uri, auth: AwsAuth)

case class ConsumerSettings(
  maxRead: Int,
  visibilityTimeout: Int,
  waitTimeSeconds: Option[Int],
  initialDelay: FiniteDuration,
  maxRetry: Int)

object ConsumerSettings {
  val default = ConsumerSettings(10, 15, None, 100.millis, 10)
}

case class AwsAuth(accessKey: String, secretKey: String, region: String)
