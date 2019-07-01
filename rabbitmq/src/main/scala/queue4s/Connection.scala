package queue4s

import cats.effect._
import com.rabbitmq.client.{
  Channel,
  ConnectionFactory,
  Connection => RmqConnection
}

private[queue4s] trait Connection {

  def connection[F[_]: Sync](
    setting: RabbitmqSetting
  ): Resource[F, RmqConnection] =
    Resource.make[F, RmqConnection] {
      Sync[F].delay {
        val factory = new ConnectionFactory()
        factory.setUsername(setting.username)
        factory.setPassword(setting.password)
        factory.setVirtualHost(setting.virtualHost)
        factory.setHost(setting.host)
        factory.setPort(setting.port)
        factory.newConnection()
      }
    } { conn =>
      Sync[F].delay(conn.close())
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
