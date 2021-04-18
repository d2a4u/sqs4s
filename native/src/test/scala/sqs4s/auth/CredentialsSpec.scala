package sqs4s.auth

import fs2.Stream
import cats.effect.{IO, Resource}
import cats.implicits._
import org.http4s.{Request, Response, Method, EntityDecoder}
import org.scalacheck.Gen
import sqs4s.{Arbitraries, IOSpec}

import scala.concurrent.duration._
import cats.effect.Ref

class CredentialsSpec extends IOSpec with Arbitraries {

  behavior.of("instanceMetadataResource")

  private def calledCounterMockClient(
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
    Resource.eval(Ref.of[IO, Int](0)).flatMap { counter =>
      Credentials.instanceMetadata(
        calledCounterMockClient(counter, genCredentials),
        2.seconds,
        1.second,
        allowRedirect = false
      )
    }.use { creds =>
      for {
        cred <- creds.get
        tmp = cred.asInstanceOf[TemporarySecurityCredential]
        ak = tmp.accessKey
        sk = tmp.secretKey
        st = tmp.sessionToken
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
    val called = Resource.eval(Ref.of[IO, Int](0)).use { counter =>
      Credentials.instanceMetadata(
        calledCounterMockClient(counter, genCredentials),
        2.second,
        1.second,
        allowRedirect = false
      ).use { _ =>
        IO.sleep(4.seconds)
      } >> counter.get
    }.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, sleep 4 seconds.
    // Hence, refresh should have been called 3 times plus 1 initial call.
    called should be >= 4
  }

  it should "get different credentials" in {
    def collect(
      ref: Ref[IO, Set[TemporarySecurityCredential]],
      creds: Credentials[IO]
    ): IO[Unit] =
      for {
        cred <- creds.get
        updated <- ref.update(
          _ ++ Set(cred.asInstanceOf[TemporarySecurityCredential])
        )
      } yield updated

    val creds = Resource.eval(Ref.of[IO, Int](0)).use { counter =>
      Credentials.instanceMetadata(
        calledCounterMockClient(counter, genCredentials),
        2.second,
        1.second,
        allowRedirect = false
      ).use { creds =>
        for {
          ref <- Ref[IO].of(Set.empty[TemporarySecurityCredential])
          _ <- {
            Stream.repeatEval(collect(
              ref,
              creds
            )).metered(100.millis).take(40).compile.drain
          }
          collected <- ref.get
        } yield collected
      }
    }.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, hence, cred is
    // refreshed every second. Credentials are collected every 100 millis into
    // a set, repeatedly 40 times (about 4 seconds)
    creds.size should be >= 4
  }
}
