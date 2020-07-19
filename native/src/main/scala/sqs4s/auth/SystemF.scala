package sqs4s.auth

import cats.effect.Sync
import sqs4s.auth.errors.{NoEnvironmentVariablesFound, NoSystemPropertiesFound}

object SystemF {
  def env[F[_]: Sync](name: String): F[String] =
    Sync[F].fromOption(
      Option(System.getenv(name)),
      NoEnvironmentVariablesFound(name)
    )

  def envOpt[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(Option(System.getenv(name)))

  def prop[F[_]: Sync](name: String): F[String] =
    Sync[F].fromOption(
      Option(System.getProperty(name)),
      NoSystemPropertiesFound(name)
    )

  def propOpt[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(Option(System.getProperty(name)))
}
