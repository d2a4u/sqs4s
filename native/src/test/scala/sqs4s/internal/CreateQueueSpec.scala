package sqs4s.internal

import cats.effect.IO
import org.http4s.client.blaze.{BlazeClientBuilder, Http1Client}
import sqs4s.internal.util.IOSpec

class CreateQueueSpec extends IOSpec {
  "CreateQueue" should "create queue when run" in {
    val setting = SqsSetting(
      "https://sqs.eu-west-1.amazonaws.com/123456789012",
      "foo",
      "bar",
      "eu-west-1"
    )

    val created = BlazeClientBuilder[IO](ec).resource
      .use { client =>
        CreateQueue("test").run[IO](setting, client)
      }
      .unsafeRunSync()
    println(created)
    created shouldBe a[String]
  }
}
