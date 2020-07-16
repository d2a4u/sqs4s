package sqs4s.auth

import cats.Applicative
import cats.effect.{Clock, Concurrent, Resource, Sync, Timer}
import cats.implicits._
import fs2._
import org.http4s.Method.{GET, PUT}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.implicits._
import org.http4s.{Header, Response, Status}
import sqs4s.auth.errors._

import scala.concurrent.duration._

trait Credentials[F[_]] {
  def get: F[Credential]
}

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

/**
  * Implementation which follows AWS doc
  * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
  */
object Credentials {
  def instance[F[_]](credF: F[Credential]): Credentials[F] =
    new Credentials[F] {
      def get: F[Credential] = credF
    }

  def basic[F[_]: Applicative](
    accessKey: String,
    secretKey: String
  ): Credentials[F] =
    Credentials.instance[F](
      BasicCredential(accessKey, secretKey).pure[F].widen
    )

  /**
    * Load Credentials in this order:
    * Environment variables
    * Java system properties
    * Instance profile credentials– used on EC2 instances, and delivered through
    * the Amazon EC2 metadata service.
    *
    * @param client        http client
    * @param ttl           session token's time to live
    * @param refreshBefore a small duration to refresh the token before it expires
    * @tparam F an effect which represents the side effects
    * @return
    */
  def chain[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes
  ): Resource[F, Credentials[F]] =
    envVar[F] orElse sysProp[F] orElse instanceMetadata[F](
      client,
      ttl,
      refreshBefore
    )

  /**
    * Create a resource of pure value Credential where the access key and secret
    * key are static
    *
    * @param accessKey static access key
    * @param secretKey static secret key
    * @tparam F an effect which represents pure value
    * @return a Resource of TemporarySecurityCredential
    */
  def basicResource[F[_]: Applicative](
    accessKey: String,
    secretKey: String
  ): Resource[F, Credentials[F]] =
    Resource.pure[F, Credentials[F]](instance(BasicCredential(
      accessKey,
      secretKey
    ).pure[F].widen[Credential]))

  def envVar[F[_]: Sync]: Resource[F, Credentials[F]] =
    Resource.liftF {
      Sync[F].delay {
        val ACCESS_KEY_ENV_VAR = "AWS_ACCESS_KEY_ID"
        val ALTERNATE_ACCESS_KEY_ENV_VAR = "AWS_ACCESS_KEY"

        val SECRET_KEY_ENV_VAR = "AWS_SECRET_KEY"
        val ALTERNATE_SECRET_KEY_ENV_VAR = "AWS_SECRET_ACCESS_KEY"

        val AWS_SESSION_TOKEN_ENV_VAR = "AWS_SESSION_TOKEN"
        val optAccessKey = Option(System.getenv(ACCESS_KEY_ENV_VAR)) |+| Option(
          System.getenv(ALTERNATE_ACCESS_KEY_ENV_VAR)
        )
        val optSecretKey = Option(System.getenv(SECRET_KEY_ENV_VAR)) |+| Option(
          System.getenv(ALTERNATE_SECRET_KEY_ENV_VAR)
        )
        val optSessionToken = Option(System.getenv(AWS_SESSION_TOKEN_ENV_VAR))

        instance[F] {
          (optAccessKey, optSecretKey, optSessionToken) match {
            case (Some(accessKey), Some(secretKey), None) =>
              BasicCredential(accessKey, secretKey)
                .pure[F].widen

            case (Some(accessKey), Some(secretKey), Some(token)) =>
              TemporarySecurityCredential(
                accessKey,
                secretKey,
                token
              ).pure[F].widen

            case _ =>
              Sync[F].raiseError[Credential](NoEnvironmentVariablesFound)
          }
        }
      }
    }

  def sysProp[F[_]: Sync]: Resource[F, Credentials[F]] =
    Resource.liftF {
      Sync[F].delay {
        val ACCESS_KEY_SYSTEM_PROPERTY = "aws.accessKeyId"
        val SECRET_KEY_SYSTEM_PROPERTY = "aws.secretKey"
        val SESSION_TOKEN_SYSTEM_PROPERTY = "aws.sessionToken"
        val optAccessKey =
          Option(System.getProperty(ACCESS_KEY_SYSTEM_PROPERTY))
        val optSecretKey =
          Option(System.getProperty(SECRET_KEY_SYSTEM_PROPERTY))
        val optSessionToken =
          Option(System.getProperty(SESSION_TOKEN_SYSTEM_PROPERTY))

        instance[F] {
          (optAccessKey, optSecretKey, optSessionToken) match {
            case (Some(accessKey), Some(secretKey), None) =>
              BasicCredential(accessKey, secretKey)
                .pure[F].widen

            case (Some(accessKey), Some(secretKey), Some(token)) =>
              TemporarySecurityCredential(
                accessKey,
                secretKey,
                token
              ).pure[F].widen

            case _ =>
              Sync[F].raiseError[Credential](NoSystemPropertiesFound)
          }
        }
      }
    }

  /**
    * Create a resource of temporary credential, credential is automatically
    * retrieved from EC2 instance metadata: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html#instance-metadata-security-credentials
    *
    * @param client        HTTP client
    * @param ttl           session token's time to live
    * @param refreshBefore a small duration to refresh the token before it expires
    * @tparam F an effect which represents the side effects
    * @return a Resource of TemporarySecurityCredential
    */
  def instanceMetadata[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes
  ): Resource[F, Credentials[F]] = {
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

    Resource.liftF {
      refresh.map { init =>
        Stream
          .repeatEval(refresh)
          .metered(ttl - refreshBefore)
          .holdResource(init).map { sig =>
            instance[F](sig.get.map { resp =>
              TemporarySecurityCredential(
                resp.accessKeyId,
                resp.secretAccessKey,
                resp.token
              )
            })
          }
      }
    }.flatten
  }
}
