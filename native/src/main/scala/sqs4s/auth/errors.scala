package sqs4s.auth

import org.http4s.Status

object errors {
  abstract class AuthError extends Exception

}
