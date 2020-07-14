package sqs4s.api.lo

import cats.MonadError
import cats.effect.{Sync, Timer}
import cats.implicits._
import fs2.Chunk
import org.http4s.client.Client
import org.http4s.scalaxml._
import org.http4s.{Request, Response, Status}
import sqs4s.api.errors.{MessageTooLarge, RetriableServerError, SqsError}
import sqs4s.api.{SqsConfig, SqsSettings}
import sqs4s.auth.BasicCredential
import sqs4s.auth.errors.UnauthorizedAuthError

import scala.xml.{Elem, XML}

abstract class Action[F[_]: Sync: Timer, T] {

  @deprecated("use SqsConfig instead", "1.1.0")
  def runWith(client: Client[F], settings: SqsSettings): F[T] =
    mkRequest(settings)
      .flatMap(req => client.expectOr[Elem](req)(handleError))
      .flatMap(parseResponse)

  @deprecated("use SqsConfig instead", "1.1.0")
  def mkRequest(settings: SqsSettings): F[Request[F]] =
    mkRequest(SqsConfig(
      settings.queue,
      BasicCredential(settings.auth.accessKey, settings.auth.secretKey),
      settings.auth.region
    ))

  def runWith(client: Client[F], config: SqsConfig): F[T] =
    mkRequest(config)
      .flatMap(req => client.expectOr[Elem](req)(handleError))
      .flatMap(parseResponse)

  def mkRequest(config: SqsConfig): F[Request[F]]

  def parseResponse(response: Elem): F[T]

  val handleError: Response[F] => F[Throwable] = error => {
    if (error.status.responseClass == Status.ServerError) {
      error.body.compile.to(Chunk)
        .map(b => RetriableServerError(new String(b.toArray)))
    } else if (error.status == Status.Unauthorized) {
      MonadError[F, Throwable].raiseError(UnauthorizedAuthError)
    } else {
      if (error.status == Status.UriTooLong) {
        MonadError[F, Throwable].raiseError(MessageTooLarge)
      } else {
        for {
          bytes <- error.body.compile.to(Chunk)
          xml <- Sync[F].delay(XML.loadString(new String(bytes.toArray)))
        } yield handleXmlError(xml)
      }
    }
  }

  val handleXmlError: Elem => Throwable = error => SqsError.fromXml(error)
}
