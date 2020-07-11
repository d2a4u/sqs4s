package sqs4s.auth

import org.http4s.Status

object errors {
  abstract class AuthError extends Exception

  case object NoInstanceProfileCredentialFound extends AuthError {
    override def getMessage: String = "Missing role"
  }

  case class UnknownAuthError(status: Status) extends AuthError {
    override def getMessage: String =
      s"Unknown error while getting credential, status ${status.code}"
  }

  case object RetriableServerError extends AuthError

  case object UnauthorizedAuthError extends AuthError
}
