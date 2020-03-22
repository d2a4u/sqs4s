package sqs4s.api

import org.http4s.Uri

case class SqsSettings(queue: Uri, auth: AwsAuth)

case class ConsumerSettings(
  maxRead: Int,
  visibilityTimeout: Int,
  waitTimeSeconds: Option[Int])

object ConsumerSettings {
  val default = ConsumerSettings(10, 15, None)
}

case class AwsAuth(accessKey: String, secretKey: String, region: String)
