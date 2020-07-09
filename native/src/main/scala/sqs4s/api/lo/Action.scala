package sqs4s.api.lo

import cats.MonadError
import cats.implicits._
import cats.effect.Sync
import fs2.Chunk
import org.http4s.client.Client
import org.http4s.{Request, Response, Status}
import sqs4s.api.SqsSettings
import sqs4s.api.errors.{MessageTooLarge, RetriableServerError, SqsError}
import org.http4s.scalaxml._

import scala.xml.{Elem, XML}

abstract class Action[F[_]: Sync, T] {

  def runWith(client: Client[F], settings: SqsSettings): F[T] =
    mkRequest(settings)
      .flatMap(req => client.expectOr[Elem](req)(handleError))
      .flatMap(parseResponse)

  def mkRequest(settings: SqsSettings): F[Request[F]]

  def parseResponse(response: Elem): F[T]

  val handleError: Response[F] => F[Throwable] = error => {
    if (error.status.responseClass == Status.ServerError) {
      error.body.compile.to(Chunk)
        .map(b => RetriableServerError(new String(b.toArray)))
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
