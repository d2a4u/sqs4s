package sqs4s.api

import org.http4s.client.Client

trait Action[F[_], T] {
  def runWith(setting: SqsSetting)(implicit client: Client[F]): F[T]
}
