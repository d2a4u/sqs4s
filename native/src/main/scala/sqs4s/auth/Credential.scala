package sqs4s.auth

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.Ref
import cats.effect.{Clock, Concurrent, Resource, Sync, Timer}
import fs2._
import org.http4s.Method.{GET, PUT}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.implicits._
import org.http4s.{Header, Response, Status}
import sqs4s.auth.errors._

import scala.concurrent.duration._

sealed trait Credential {
  def accessKey: String
  def secretKey: String
}

case class BasicCredential(accessKey: String, secretKey: String)
    extends Credential

case class TemporarySecurityCredential(
  accessKey: String,
  secretKey: String,
  sessionToken: String
) extends Credential

object Credential {

  /**
    * Create a resource of pure value Credential where the access key and secret
    * key are static
    * @param accessKey static access key
    * @param secretKey static secret key
    * @tparam F a effect which represents pure value
    * @return a Resource of TemporarySecurityCredential
    */
  def basicResource[F[_]: Applicative](
    accessKey: String,
    secretKey: String
  ): Resource[F, BasicCredential] =
    Resource.pure[F, BasicCredential](BasicCredential(accessKey, secretKey))

  /**
    * Create a resource of temporary credential, credential is automatically
    * retrieved from EC2 instance metadata: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html#instance-metadata-security-credentials
    * @param client HTTP client
    * @param ttl session token's time to live
    * @param refreshBefore a small duration to refresh the token before it expires
    * @tparam F an effect which represents the side effects
    * @return a Resource of TemporarySecurityCredential
    */
  def instanceMetadataResource[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes
  ): Resource[F, TemporarySecurityCredential] =
    Resource {
      val dsl = new Http4sClientDsl[F] {}
      import dsl._

      // Static address to retrieve cred from per API doc: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html#instance-metadata-security-credentials
      // and AWS SDK: https://github.com/aws/aws-sdk-java/blob/542606ddd5ce6f0c3570ef7488c4f5479c6425bb/aws-java-sdk-core/src/main/java/com/amazonaws/util/EC2MetadataUtils.java#L73
      val awsLinkLocal = uri"http://169.254.169.254"
      val tokenEndpoint = awsLinkLocal / "latest" / "api" / "token"
      val credsEndpoint =
        awsLinkLocal / "latest" / "meta-data" / "iam" / "security-credentials"
      val ttlHeader = "X-aws-ec2-metadata-token-ttl-seconds"
      val tokenHeader = "X-aws-ec2-metadata-token"

      val onError: Response[F] => F[Throwable] = resp => {
        if (resp.status.responseClass == Status.ServerError) {
          RetriableServerError.pure[F].widen
        } else {
          UnknownAuthError(resp.status).pure[F].widen
        }
      }

      val refresh: F[CredentialResponse] =
        for {
          token <- client.expectOr[String](PUT(tokenEndpoint))(onError)
          creds <- client.expectOr[List[CredentialResponse]](
            GET(
              credsEndpoint,
              Header(ttlHeader, ttl.toSeconds.toString),
              Header(tokenHeader, token)
            )
          )(onError)
          cred <- Sync[F].fromOption(
            creds.headOption,
            NoInstanceProfileCredentialFound
          )
        } yield cred

      def refreshAsync(ref: Ref[F, Option[CredentialResponse]])
        : Stream[F, Unit] = {
        val constRefresh = Stream.repeatEval(refresh).metered(
          ttl - refreshBefore
        ).evalMap(cred => ref.set(cred.some)).compile.drain
        Stream.retry(
          constRefresh,
          0.second,
          _ => 1.second,
          60,
          {
            case RetriableServerError => true
            case _ => false
          }
        )
      }

      Ref.of[F, Option[CredentialResponse]](None).flatMap { ref =>
        for {
          fiber <- refreshAsync(ref).compile.drain.start
          now <- Clock[F].realTime(TimeUnit.MILLISECONDS)
          tmp <- ref.get.flatMap {
            case Some(cred) if cred.expiration.toEpochMilli > now =>
              TemporarySecurityCredential(
                cred.accessKeyId,
                cred.secretAccessKey,
                cred.token
              ).pure[F]

            case _ =>
              refresh.flatMap(cred =>
                ref.set(cred.some).as(TemporarySecurityCredential(
                  cred.accessKeyId,
                  cred.secretAccessKey,
                  cred.token
                )))

          }
        } yield tmp -> (fiber.cancel: F[Unit])
      }
    }
}
