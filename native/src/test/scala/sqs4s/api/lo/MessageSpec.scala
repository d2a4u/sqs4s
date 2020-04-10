package sqs4s.api.lo

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.{Clock, IO}
import cats.implicits._
import fs2._
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import sqs4s.api.{AwsAuth, SqsSettings}
import sqs4s.internal.aws4.IOSpec
import sqs4s.serialization.{SqsDeserializer, SqsSerializer}

class MessageSpec extends IOSpec {
  override implicit lazy val testClock: Clock[IO] = new Clock[IO] {
    def realTime(unit: TimeUnit): IO[Long] = IO.delay {
      Instant.now().toEpochMilli
    }

    def monotonic(unit: TimeUnit): IO[Long] = IO(0L)
  }

  val accessKey = sys.env("ACCESS_KEY")
  val secretKey = sys.env("SECRET_KEY")
  val awsAccountId = sys.env("AWS_ACCOUNT_ID")
  val queue = Uri.unsafeFromString(
    s"https://sqs.eu-west-1.amazonaws.com/$awsAccountId/test"
  )

  behavior.of("Message API")

  it should "send and receive message" in {
    val setting = SqsSettings(queue, AwsAuth(accessKey, secretKey, "eu-west-1"))

    implicit val desrlz = new SqsDeserializer[IO, Int] {
      override def deserialize(u: String): IO[Int] = IO(u.toInt)
    }

    implicit val srlz = new SqsSerializer[Int] {
      override def serialize(t: Int): String = t.toString
    }

    val inputs = 1 to 10
    BlazeClientBuilder[IO](ec).resource
      .use { implicit client =>
        inputs.toList
          .map(i => SendMessage[IO, Int](i))
          .traverse(_.runWith(setting))
      }
      .unsafeRunSync()

    def ack(implicit c: Client[IO]): Pipe[IO, ReceiveMessage.Result[Int], Int] =
      _.flatMap { res =>
        val r = DeleteMessage[IO](res.receiptHandle)
          .runWith(setting)
          .as(res.body)
        Stream.eval(r)
      }

    def takeWhileCond(n: Int): Pipe[IO, Int, Int] = {
      var soFar: List[Int] = List.empty
      in => {
        in.takeWhile { i =>
          if (!soFar.contains(i) && soFar.length < n) {
            soFar = soFar :+ i
            true
          } else if (soFar.length == n) {
            false
          } else true
        }
      }
    }

    val polled = BlazeClientBuilder[IO](ec).resource.use { implicit client =>
      val read1 = ReceiveMessage[IO, Int](10).runWith(setting)
      val read = Stream.repeatEval(read1).flatMap(Stream.emits)

      val result = read.broadcastThrough(ack)
      takeWhileCond(10).apply(result).take(10).compile.toList
    }

    polled.unsafeRunSync() should contain theSameElementsAs inputs
  }
}
