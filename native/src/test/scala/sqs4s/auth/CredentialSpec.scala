package sqs4s.auth

import fs2.Stream
import cats.effect.concurrent.Ref
import cats.effect.{IO, Resource}
import cats.implicits._
import org.http4s._
import org.scalacheck.Gen
import sqs4s.{Arbitraries, IOSpec}

import scala.concurrent.duration._

class CredentialSpec extends IOSpec with Arbitraries {

  behavior.of("instanceMetadataResource")

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
            ).asInstanceOf[A].pure[IO]
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
      val tmp = cred.asInstanceOf[TemporarySecurityCredential[IO]]
      for {
        ak <- tmp.accessKey
        sk <- tmp.secretKey
        st <- tmp.sessionToken
      } yield (ak, sk, st)
    }.unsafeRunSync() match {
      case (acc, scr, tok) =>
        acc shouldBe a[String]
        scr shouldBe a[String]
        tok shouldBe a[String]

      case _ =>
        fail()
    }
  }

  it should "refresh token periodically when it expires" in {
    val called = Resource.liftF(Ref.of[IO, Int](0)).use { counter =>
      Credential.instanceMetadataResource(
        calledCounterMockClient(counter, genCredentials),
        2.second,
        1.second
      ).use { _ =>
        IO.sleep(4.seconds)
      } >> counter.get
    }.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, sleep 4 seconds.
    // Hence, refresh should have been called 3 times plus 1 initial call.
    called should be >= 4
  }

  it should "get different credentials" in {
    case class TmpCredential(
      accessKey: String,
      secretKey: String,
      sessionToken: String
    )

    def collect(
      ref: Ref[IO, Set[TmpCredential]],
      cred: TemporarySecurityCredential[IO]
    ): IO[Unit] =
      for {
        ak <- cred.accessKey
        sk <- cred.secretKey
        st <- cred.sessionToken
        updated <- ref.update(_ ++ Set(TmpCredential(ak, sk, st)))
      } yield updated

    val creds = Resource.liftF(Ref.of[IO, Int](0)).use { counter =>
      Credential.instanceMetadataResource(
        calledCounterMockClient(counter, genCredentials),
        2.second,
        1.second
      ).use { cred =>
        Ref[IO].of(Set.empty[TmpCredential]).flatMap { ref =>
          Stream.repeatEval(collect(
            ref,
            cred.asInstanceOf[TemporarySecurityCredential[IO]]
          )).metered(100.millis).take(40).compile.drain >> ref.get
        }
      }
    }.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, hence, cred is
    // refreshed every second. Credentials are collected every 100 millis into
    // a set, repeatedly 40 times (about 4 seconds)
    creds.size should be >= 4
  }
}
