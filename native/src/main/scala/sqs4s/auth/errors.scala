package sqs4s.auth

import org.http4s.Status

object errors {
  abstract class AuthError extends Exception

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

  final case object RetriableServerError extends AuthError

  final case object UnauthorizedAuthError extends AuthError
}
