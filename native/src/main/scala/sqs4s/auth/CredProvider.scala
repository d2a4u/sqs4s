package sqs4s.auth

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Clock, Concurrent, Sync, Timer}
import fs2._
import org.http4s.Method.{GET, PUT}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.implicits._
import org.http4s.{Header, Response, Status}
import sqs4s.auth.errors._

import scala.concurrent.duration._

trait CredProvider[F[_]] {
  def accessKey: F[String]
  def secretKey: F[String]
}

trait SessionCredProfiler[F[_]] {
  def sessionToken: F[String]
}

trait TemporaryCredProvider[F[_]]
    extends CredProvider[F]
    with SessionCredProfiler[F] {
  def refresh: F[Credential]
}

case class BasicCredProvider[F[_]: Applicative](
  accessK: String,
  secretK: String
) extends CredProvider[F] {
  def accessKey: F[String] = accessK.pure[F]
  def secretKey: F[String] = secretK.pure[F]
}

class InstanceProfileCredProvider[F[_]: Sync: Clock: Timer](
  client: Client[F],
  ref: Ref[F, Option[Credential]],
  ttl: FiniteDuration,
  refreshBefore: FiniteDuration
) extends TemporaryCredProvider[F]
    with Http4sClientDsl[F] {

  private val linkLocal = uri"http://169.254.169.254"
  private val tokenEndpoint = linkLocal / "latest" / "api" / "token"
  private val credsEndpoint =
    linkLocal / "latest" / "meta-data" / "iam" / "security-credentials"
  private val ttlHeader = "X-aws-ec2-metadata-token-ttl-seconds"
  private val tokenHeader = "X-aws-ec2-metadata-token"

  val onError: Response[F] => F[Throwable] = resp => {
    if (resp.status.responseClass == Status.ServerError) {
      RetriableServerError.pure[F].widen
    } else {
      UnknownAuthError(resp.status).pure[F].widen
    }
  }

  val refresh: F[Credential] = {
    val newCred =
      for {
        token <- client.expectOr[String](PUT(tokenEndpoint))(onError)
        creds <- client.expectOr[List[Credential]](
          GET(
            credsEndpoint,
            Header(ttlHeader, ttl.toSeconds.toString),
            Header(tokenHeader, token)
          )
        )(onError)
        cr <- Sync[F].fromOption(
          creds.headOption,
          NoInstanceProfileCredentialFound
        )
      } yield cr
    for {
      now <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      optCred <- ref.get
      refreshed <- optCred match {
        case Some(cred) if cred.expiration.getEpochSecond > now =>
          cred.pure[F]

        case _ =>
          newCred.flatMap(cred => ref.set(cred.some).as(cred))
      }
    } yield refreshed
  }

  val refreshAsync: Stream[F, Unit] = {
    val constRefresh = Stream.repeatEval(refresh).metered(
      ttl - refreshBefore
    ).compile.drain
    Stream.retry(
      constRefresh,
      0.second,
      _ => 1.second,
      10,
      {
        case RetriableServerError => true
        case _ => false
      }
    )
  }

  val accessKey: F[String] =
    ref.get.flatMap {
      case Some(cred) => cred.accessKeyId.pure[F]
      case None => refresh.map(_.accessKeyId)
    }

  val secretKey: F[String] =
    ref.get.flatMap {
      case Some(cred) => cred.secretAccessKey.pure[F]
      case None => refresh.map(_.secretAccessKey)
    }

  val sessionToken: F[String] =
    ref.get.flatMap {
      case Some(cred) => cred.token.pure[F]
      case None => refresh.map(_.token)
    }
}

object InstanceProfileCredProvider {

  def instance[F[_]: Sync: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 1.minute
  ): F[InstanceProfileCredProvider[F]] =
    Ref.of[F, Option[Credential]](None).map(cred =>
      new InstanceProfileCredProvider[F](client, cred, ttl, refreshBefore))

  /**
    * Create an instance of InstanceProfileCredProvider but start a refresh
    * stream in another thread which rotate credential every ttlDefault - 60 seconds
    * @param client http client
    * @param ttl token's ttl (token's rotation interval)
    * @tparam F a concurrent effect so that token can be refresh in another thread
    * @return an instance of InstanceProfileCredProvider
    */
  def asyncRefreshInstance[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 1.minute
  ): F[InstanceProfileCredProvider[F]] =
    Ref.of[F, Option[Credential]](None).flatMap { cred =>
      val provider =
        new InstanceProfileCredProvider[F](client, cred, ttl, refreshBefore)
      provider.refreshAsync.compile.drain.start.as(provider)
    }
}
