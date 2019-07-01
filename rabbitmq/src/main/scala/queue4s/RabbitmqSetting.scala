package queue4s

case class RabbitmqSetting(
  username: String,
  password: String,
  virtualHost: String,
  host: String,
  port: Int)
