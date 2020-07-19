package sqs4s.auth

import org.http4s.Status

object errors {
  abstract class AuthError extends Exception

  case object NoInstanceProfileCredentialFound extends AuthError {
    override def getMessage: String = "Missing role"
  }

  case class NoEnvironmentVariablesFound(name: String) extends AuthError {
    override def getMessage: String =
      s"Missing $name environment variable"
  }

  case class NoSystemPropertiesFound(name: String) extends AuthError {
    override def getMessage: String =
      s"Missing $name system property"
  }

  case class UnknownAuthError(status: Status) extends AuthError {
    override def getMessage: String =
      s"Unknown error while getting credential, status ${status.code}"
  }

  case object NoValidAuthMethodError extends AuthError {
    override def getMessage: String =
      "Could not find valid credential in credentials chain"
  }

  case object RetriableServerError extends AuthError

  case object UnauthorizedAuthError extends AuthError
}
