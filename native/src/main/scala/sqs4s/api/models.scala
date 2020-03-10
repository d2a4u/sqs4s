package sqs4s.api

import org.http4s.Uri

case class SqsSetting(url: Uri, auth: AwsAuth)

case class AwsAuth(accessKey: String, secretKey: String, region: String)
