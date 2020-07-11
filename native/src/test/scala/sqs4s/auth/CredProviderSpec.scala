package sqs4s.auth

import java.time.Instant

import cats.implicits._
import cats.effect.IO
import cats.effect.concurrent.Ref
import org.http4s.{EntityDecoder, Method, Request, Response}
import sqs4s.{Arbitraries, IOSpec}

import scala.concurrent.duration._

class CredProviderSpec extends IOSpec with Arbitraries {

  behavior.of("InstanceProfileCredProvider async instance")

  it should "get credential on initialization" in {
    val mockClient = new MockClient[IO] {
      override def expectOr[A](req: IO[Request[IO]])(
        onError: Response[IO] => IO[Throwable]
      )(implicit d: EntityDecoder[IO, A]): IO[A] = {
        req.flatMap { r =>
          if (r.method == Method.PUT) {
            arb[String].asInstanceOf[A].pure[IO]
          } else if (r.method == Method.GET) {
            gen[List[Credential]].asInstanceOf[A].pure[IO]
          } else {
            IO.raiseError(new Exception("Unknown method called"))
          }
        }
      }
    }

    val providerF = InstanceProfileCredProvider.asyncRefreshInstance[IO](
      mockClient,
      2.seconds,
      1.second
    )

    val cred = for {
      provider <- providerF
      accessKey <- provider.accessKey
      secretKey <- provider.secretKey
      token <- provider.sessionToken
    } yield (accessKey, secretKey, token)
    cred.unsafeRunSync() match {
      case (acc, scr, tok) =>
        acc shouldBe a[String]
        scr shouldBe a[String]
        tok shouldBe a[String]

      case _ =>
        fail()
    }
  }

  it should "refresh token periodically when it expires" in {
    val expiredCreds = genCredentials.map { creds =>
      creds.map { cred =>
        cred.copy(expiration = Instant.now.minusSeconds(10))
      }
    }
    val calledCounter: IO[Ref[IO, Int]] = Ref.of(0)

    def mockClient(ref: Ref[IO, Int]) =
      new MockClient[IO] {
        override def expectOr[A](req: IO[Request[IO]])(
          onError: Response[IO] => IO[Throwable]
        )(implicit d: EntityDecoder[IO, A]): IO[A] = {
          req.flatMap { r =>
            if (r.method == Method.PUT) {
              arb[String].asInstanceOf[A].pure[IO]
            } else if (r.method == Method.GET) {
              ref.update(_ + 1) >> gen[List[Credential]](
                expiredCreds
              ).asInstanceOf[A].pure[IO]
            } else {
              IO.raiseError(new Exception("Unknown method called"))
            }
          }
        }
      }

    val cred = for {
      ref <- calledCounter
      client = mockClient(ref)
      _ <- InstanceProfileCredProvider.asyncRefreshInstance[IO](
        client,
        2.seconds,
        1.second
      )
      _ <- IO.sleep(12.seconds)
      called <- ref.get
    } yield called
    val called = cred.unsafeRunSync()

    // token TTL is 2 seconds, refresh is called 1 second early, sleep 12 seconds.
    // Hence, at least, refresh should have been called 10 times. (12 * (2 - 1) = 12)
    called >= 10 shouldBe true
  }
}
