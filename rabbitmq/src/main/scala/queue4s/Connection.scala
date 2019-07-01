package queue4s

import cats.MonadError
import cats.effect._
import com.rabbitmq.client.{Channel, ConnectionFactory, Connection => RmqConnection}

import scala.util.Try

private[queue4s] trait Connection {

  def connection[F[_]: Sync](
    setting: RabbitmqSetting
  ): Resource[F, RmqConnection] =
    Resource.make[F, RmqConnection] {
      val conn = Try {
        val factory = new ConnectionFactory()
        factory.setUsername(setting.username)
        factory.setPassword(setting.password)
        factory.setVirtualHost(setting.virtualHost)
        factory.setHost(setting.host)
        factory.setPort(setting.port)
        factory.newConnection()
      }
      MonadError[F, Throwable].fromTry(conn)
    } { conn =>
      Sync[F].defer(MonadError[F, Throwable].fromTry(Try(conn.close())))
    }

  def channel[F[_]: Sync](
    connection: Resource[F, RmqConnection]
  ): Resource[F, Channel] =
    for {
      conn <- connection
      chnl <- Resource.make[F, Channel] {
        Sync[F].delay(conn.createChannel())
      } { c =>
        Sync[F].delay(c.close())
      }
    } yield chnl
}
