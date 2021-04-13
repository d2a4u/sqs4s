package sqs4s.auth

import cats.syntax.all._
import cats.effect.Sync
import sqs4s.auth.errors.{NoEnvironmentVariablesFound, NoSystemPropertiesFound}

object SystemF {
  def env[F[_]: Sync](name: String): F[String] =
    envOpt[F](name).flatMap { opt =>
      Sync[F].fromOption(opt, NoEnvironmentVariablesFound(name))
    }

  def envOpt[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(Option(System.getenv(name)))

  def prop[F[_]: Sync](name: String): F[String] =
    propOpt[F](name).flatMap { opt =>
      Sync[F].fromOption(opt, NoSystemPropertiesFound(name))
    }

  def propOpt[F[_]: Sync](name: String): F[Option[String]] =
    Sync[F].delay(Option(System.getProperty(name)))
}
