package sqs4s.api
package lo

import cats.MonadError
import cats.effect.{Sync, Timer}
import cats.syntax.all._
import fs2.Chunk
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Request, Response, Status}
import org.typelevel.log4cats.Logger
import sqs4s.api.errors._
import sqs4s.auth.errors.UnauthorizedAuthError

import scala.concurrent.duration._
import scala.xml.{Elem, XML}

abstract class Action[F[_]: Sync: Timer, T] {
  val version = List("Version" -> "2012-11-05")

  def runWith(
    client: Client[F],
    config: SqsConfig[F],
    logger: Logger[F]
  ): F[T] = {
    val reqToRes =
      mkRequest(config, logger)
        .flatMap(req => client.expectOr[Elem](req)(parseError))
        .flatMap(parseResponse)

    withRetry(reqToRes)
  }

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]]

  def parseResponse(response: Elem): F[T]

  def parseError(error: Response[F]): F[Throwable] = {
    if (error.status.responseClass == Status.ServerError) {
      error.body.compile.to(Chunk)
        .map(b => RetriableServerError(new String(b.toArray)))
    } else if (error.status == Status.Unauthorized) {
      MonadError[F, Throwable].raiseError(UnauthorizedAuthError)
    } else if (error.status == Status.UriTooLong) {
      MonadError[F, Throwable].raiseError(MessageTooLarge)
    } else {
      for {
        bytes <- error.body.compile.to(Chunk)
        xml <- Sync[F].delay(XML.loadString(new String(bytes.toArray)))
      } yield SqsError.fromXml(xml)
    }
  }

  private def withRetry[U](f: F[U]) = {
    fs2.Stream.retry[F, U](
      f,
      10.millis,
      _ => 10.millis,
      10,
      {
        case _: ExpiredTokenError => true
        case _ => false
      }
    ).compile.lastOrError
  }
}
