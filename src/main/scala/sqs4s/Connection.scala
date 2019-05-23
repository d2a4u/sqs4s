package sqs4s

import cats.MonadError
import cats.effect._
import com.amazon.sqs.javamessaging._
import com.amazonaws.services.sqs.AmazonSQSAsync
import javax.jms.Session

import scala.util.Try

private[sqs4s] trait Connection {

  def connection[F[_]: Sync](
    client: AmazonSQSAsync
  ): Resource[F, SQSConnection] =
    Resource.make[F, SQSConnection] {
      val sqsConn = Try {
        val connFactory =
          new SQSConnectionFactory(new ProviderConfiguration(), client)
        val conn = connFactory.createConnection()
        conn
      }
      MonadError[F, Throwable].fromTry(sqsConn)
    } { sqsConn =>
      Sync[F].defer(MonadError[F, Throwable].fromTry(Try(sqsConn.close())))
    }

  def session[F[_]: Sync](
    acknowledgeMode: Int
  ): SQSConnection => Resource[F, Session] =
    conn =>
      Resource.make[F, Session](
        MonadError[F, Throwable]
          .fromTry(Try(conn.createSession(false, acknowledgeMode)))
      )(
        sess =>
          Sync[F].defer(
            MonadError[F, Throwable]
              .fromTry(Try(sess.close()))
          )
      )

  def queue[F[_]: Sync](
    queueName: String
  ): Session => Resource[F, javax.jms.Queue] =
    sess =>
      Resource.liftF(
        Sync[F].defer(
          MonadError[F, Throwable].fromTry(Try(sess.createQueue(queueName)))
        )
      )
}
