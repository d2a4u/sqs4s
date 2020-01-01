package sqs4s.api.lo

import org.http4s.client.Client
import sqs4s.api.SqsSetting

trait Action[F[_], T] {
  def runWith(setting: SqsSetting)(implicit client: Client[F]): F[T]
}
