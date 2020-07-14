package sqs4s.auth

import cats.effect.{IO, Resource}
import cats.effect.concurrent.Ref
import cats.implicits._
import org.http4s._
import org.scalacheck.Gen
import sqs4s.{Arbitraries, IOSpec}

import scala.concurrent.duration._

class CredentialSpec extends IOSpec with Arbitraries {

  behavior.of("InstanceProfileCredential")

  def calledCounterMockClient(
    ref: Ref[IO, Int],
    genCreds: Gen[List[CredentialResponse]]
  ) =
    new MockClient[IO] {
      override def expectOr[A](req: IO[Request[IO]])(
        onError: Response[IO] => IO[Throwable]
      )(implicit d: EntityDecoder[IO, A]): IO[A] = {
        req.flatMap { r =>
          if (r.method == Method.PUT) {
            arb[String].asInstanceOf[A].pure[IO]
          } else if (r.method == Method.GET) {
            ref.update(_ + 1) >> gen[List[CredentialResponse]](
              genCreds
            ).asInstanceOf[
              A
            ].pure[IO]
          } else {
            IO.raiseError(new Exception("Unknown method called"))
          }
        }
      }
    }

  it should "get credential on initialization" in {
    Resource.liftF(Ref.of[IO, Int](0)).flatMap { counter =>
      Credential.instanceMetadataResource(
        calledCounterMockClient(counter, genCredentials),
        2.seconds,
        1.second
      )
    }.use { cred =>
      (cred.accessKey, cred.secretKey, cred.sessionToken).pure[IO]
    }.unsafeRunSync() match {
      case (acc, scr, tok) =>
        acc shouldBe a[String]
        scr shouldBe a[String]
        tok shouldBe a[String]

      case _ =>
        fail()
    }
  }

  it should "refresh token periodically when it expires (async instance)" in {
    val called = Resource.liftF(Ref.of[IO, Int](0)).use { counter =>
      Credential.instanceMetadataResource(
        calledCounterMockClient(counter, genCredentials),
        2.second,
        1.second
      ).use { _ =>
        IO.sleep(3.seconds)
      } >> counter.get
    }.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, sleep 3 seconds.
    // Hence, at least, refresh should have been called 3 times plus 1 initial call.
    println(called)
    called shouldBe 4
  }
}
