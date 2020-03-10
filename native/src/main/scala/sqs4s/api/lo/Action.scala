package sqs4s.api.lo

import cats.implicits._
import cats.effect.Sync
import org.http4s.Response
import org.http4s.client.Client
import sqs4s.api.SqsSetting
import sqs4s.api.errors.SqsError

import scala.xml.XML

abstract class Action[F[_]: Sync, T] {
  def runWith(setting: SqsSetting)(implicit client: Client[F]): F[T]

  val handleError: Response[F] => F[Throwable] = error => {
    for {
      bytes <- error.body.compile.toChunk
      xml <- Sync[F].delay(XML.loadString(new String(bytes.toArray)))
    } yield SqsError.fromXml(error.status, xml)
  }
}
