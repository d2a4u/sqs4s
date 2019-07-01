package sqs4s

import cats.effect._
import com.amazon.sqs.javamessaging._
import com.amazonaws.services.sqs.AmazonSQSAsync
import javax.jms.Session

private[sqs4s] trait Connection {

  def connection[F[_]: Sync](
    client: AmazonSQSAsync
  ): Resource[F, SQSConnection] =
    Resource.make[F, SQSConnection] {
      Sync[F].delay {
        val connFactory =
          new SQSConnectionFactory(new ProviderConfiguration(), client)
        connFactory.createConnection()
      }
    }(sqsConn => Sync[F].delay(sqsConn.close()))

  def session[F[_]: Sync](
    acknowledgeMode: Int
  ): SQSConnection => Resource[F, Session] =
    conn =>
      Resource.make[F, Session](
        Sync[F].delay(conn.createSession(false, acknowledgeMode))
      )(sess => Sync[F].delay(sess.close()))

  def queue[F[_]: Sync](
    queueName: String
  ): Session => Resource[F, javax.jms.Queue] =
    sess => Resource.liftF(Sync[F].delay(sess.createQueue(queueName)))
}
