package sqs4s.auth

import org.http4s.Status

object errors {
  abstract class AuthError extends Exception

  case object NoInstanceProfileCredentialFound extends AuthError {
    override def getMessage: String = "Missing role"
  }

  case object NoEnvironmentVariablesFound extends AuthError {
    override def getMessage: String =
      "Missing AWS_ACCESS_KEY_ID and/or AWS_SECRET_KEY environment variables"
  }

  case object NoSystemPropertiesFound extends AuthError {
    override def getMessage: String =
      "Missing aws.accessKeyId and/or aws.secretKey system properties"
  }

  case class UnknownAuthError(status: Status) extends AuthError {
    override def getMessage: String =
      s"Unknown error while getting credential, status ${status.code}"
  }

  case object RetriableServerError extends AuthError

  case object UnauthorizedAuthError extends AuthError
}
