package sqs4s

import cats.Show
import cats.implicits._
import cats.data.NonEmptyList
import org.http4s.Status
import sqs4s.api.lo.DeleteMessageBatch

import scala.xml.Elem

object errors {
  private val RetriableErrorCodes = Set(
    "ExpiredToken",
    "IDPCommunicationError",
    "IDPRejectedClaim",
    "InvalidIdentityToken",
    "RequestExpired",
    "InternalFailure",
    "NotAuthorized",
    "ThrottlingException"
  )

  abstract class SqsError extends Exception

  abstract class AuthError extends SqsError

  final case object NoInstanceProfileCredentialFound extends AuthError {
    override def getMessage: String = "Missing role"
  }

  final case class NoEnvironmentVariablesFound(name: String) extends AuthError {
    override def getMessage: String =
      s"Missing $name environment variable"
  }

  final case class NoSystemPropertiesFound(name: String) extends AuthError {
    override def getMessage: String =
      s"Missing $name system property"
  }

  final case class UnknownAuthError(status: Status) extends AuthError {
    override def getMessage: String =
      s"Unknown error while getting credential, status ${status.code}"
  }

  final case object NoValidAuthMethodError extends AuthError {
    override def getMessage: String =
      "Could not find valid credential in credentials chain"
  }

  final case object UnauthorizedAuthError extends AuthError

  final case class RetriableTokenError(message: String, requestId: String)
      extends AuthError {
    override def getMessage: String = message
  }

  final case class RetriableServerError(raw: String) extends SqsError

  final case class BasicAwsSqsError(raw: Elem) extends SqsError {
    override def getMessage: String =
      s"Raw: ${raw.toString}"
  }

  final case class UnexpectedResponseError(elemName: String, raw: Elem)
      extends SqsError {
    override def getMessage: String =
      s"""Expect XML element: $elemName
         |Raw: ${raw.toString}
     """.stripMargin
  }

  final case object MessageTooLarge extends SqsError {
    override def getMessage: String =
      "Maximum SQS message size is 256KB or maximum batch of 10 entries"
  }

  final case class AwsSqsError(
    `type`: String,
    code: String,
    message: String,
    requestId: String
  ) extends SqsError {
    override def getMessage: String =
      s"""Type: ${`type`}
         |Code: $code
         |Request ID: $requestId
         |Message: $message""".stripMargin
  }

  final case class DeleteMessageBatchErrors(
    errors: NonEmptyList[DeleteMessageBatch.Error]
  ) extends SqsError

  object SqsError {
    implicit val show: Show[SqsError] = Show.show[SqsError](_.getMessage)

    def fromXml(xml: Elem): SqsError = {
      (
        (xml \\ "Type").headOption.map(_.text),
        (xml \\ "Code").headOption.map(_.text),
        (xml \\ "Message").headOption.map(_.text),
        (xml \\ "RequestId").headOption.map(_.text)
      ).mapN { (typ, code, msg, id) =>
        code match {
          case c if RetriableErrorCodes.contains(c) =>
            RetriableTokenError(msg, id)
          case _ =>
            AwsSqsError(typ, code, msg, id)
        }
      }.getOrElse(BasicAwsSqsError(xml))
    }
  }
}
