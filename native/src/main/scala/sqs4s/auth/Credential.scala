package sqs4s.auth

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.implicits._
import cats.effect.implicits._
import cats.effect.concurrent.{Deferred, Ref}
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

sealed trait Credential[F[_]] {
  def accessKey: F[String]
  def secretKey: F[String]
}

case class BasicCredential[F[_]](accessKey: F[String], secretKey: F[String])
    extends Credential[F]

object BasicCredential {
  def apply[F[_]: Applicative](
    accessKey: String,
    secretKey: String
  ): BasicCredential[F] =
    BasicCredential[F](accessKey.pure[F], secretKey.pure[F])
}

case class TemporarySecurityCredential[F[_]](
  accessKey: F[String],
  secretKey: F[String],
  sessionToken: F[String]
) extends Credential[F]

/**
  * Implementation which follows AWS doc
  * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
  */
object Credential {

  /**
    * This order:
    * Environment variables
    * Java system properties
    * Instance profile credentials– used on EC2 instances, and delivered through
    * the Amazon EC2 metadata service.
    * @param client
    * @param ttl
    * @param refreshBefore
    * @tparam F
    * @return
    */
  def chainResource[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes
  ): Resource[F, Credential[F]] = {
    Resource {
      envVar[F].map(cred => cred -> ().pure[F]).handleErrorWith(_ =>
        sysProp[F].map(cred => cred -> ().pure[F])).handleErrorWith(_ =>
        instanceMetadata[F](client, ttl, refreshBefore))
    }
  }

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
  ): Resource[F, Credential[F]] =
    Resource.pure[F, BasicCredential[F]](BasicCredential[F](
      accessKey.pure[F],
      secretKey.pure[F]
    ))

  def envVarResource[F[_]: Sync]: Resource[F, Credential[F]] =
    Resource.liftF(Sync[F].suspend(envVar[F]))

  private def envVar[F[_]: Sync]: F[Credential[F]] = {
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

    (optAccessKey, optSecretKey, optSessionToken) match {
      case (Some(accessKey), Some(secretKey), None) =>
        BasicCredential[F](accessKey.pure[F], secretKey.pure[F])
          .pure[F].widen[Credential[F]]

      case (Some(accessKey), Some(secretKey), Some(token)) =>
        TemporarySecurityCredential[F](
          accessKey.pure[F],
          secretKey.pure[F],
          token.pure[F]
        ).pure[F].widen[Credential[F]]

      case _ =>
        Sync[F].raiseError[Credential[F]](NoEnvironmentVariablesFound)
    }
  }

  def sysPropResource[F[_]: Sync]: Resource[F, Credential[F]] =
    Resource.liftF(Sync[F].suspend(sysProp[F]))

  private def sysProp[F[_]: Sync]: F[Credential[F]] = {
    val ACCESS_KEY_SYSTEM_PROPERTY = "aws.accessKeyId"
    val SECRET_KEY_SYSTEM_PROPERTY = "aws.secretKey"
    val SESSION_TOKEN_SYSTEM_PROPERTY = "aws.sessionToken"
    val optAccessKey = Option(System.getProperty(ACCESS_KEY_SYSTEM_PROPERTY))
    val optSecretKey = Option(System.getProperty(SECRET_KEY_SYSTEM_PROPERTY))
    val optSessionToken =
      Option(System.getProperty(SESSION_TOKEN_SYSTEM_PROPERTY))

    (optAccessKey, optSecretKey, optSessionToken) match {
      case (Some(accessKey), Some(secretKey), None) =>
        BasicCredential[F](accessKey.pure[F], secretKey.pure[F])
          .pure[F].widen[Credential[F]]

      case (Some(accessKey), Some(secretKey), Some(token)) =>
        TemporarySecurityCredential[F](
          accessKey.pure[F],
          secretKey.pure[F],
          token.pure[F]
        ).pure[F].widen[Credential[F]]

      case _ =>
        Sync[F].raiseError[Credential[F]](NoSystemPropertiesFound)
    }
  }

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
  ): Resource[F, Credential[F]] =
    Resource {
      instanceMetadata[F](client, ttl, refreshBefore)
    }

  def instanceMetadata[F[_]: Concurrent: Clock: Timer](
    client: Client[F],
    ttl: FiniteDuration,
    refreshBefore: FiniteDuration
  ): F[(Credential[F], F[Unit])] = {
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

    sealed trait CredentialState
    case class Initial(value: Deferred[F, CredentialResponse])
        extends CredentialState
    case class Current(value: CredentialResponse) extends CredentialState

    object CredentialState {
      def init: F[CredentialState] =
        Deferred[F, CredentialResponse].map(v => Initial(v): CredentialState)
    }

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

    CredentialState.init.flatMap(Ref[F].of).flatMap { credentialState =>
      def updateState(v: CredentialResponse): F[Unit] =
        credentialState.modify {
          case Initial(waiting) => Current(v) -> waiting.complete(v)
          case Current(_) => Current(v) -> ().pure[F]
        }.flatten

      val tmpCred = {
        val credResp =
          for {
            resp <- credentialState.get
              .flatMap {
                case Initial(waiting) => waiting.get
                case Current(v) => v.pure[F]
              }
            now <- Clock[F].realTime(TimeUnit.MILLISECONDS)
            newResp <- if (resp.expiration.toEpochMilli > now) {
              resp.pure[F]
            } else {
              refresh.flatMap(resp => updateState(resp).as(resp))
            }
          } yield newResp

        TemporarySecurityCredential[F](
          credResp.map(_.accessKeyId),
          credResp.map(_.secretAccessKey),
          credResp.map(_.token)
        )
      }

      val asyncRefresh = {
        val constRefresh =
          (Stream.eval(refresh) ++ Stream.repeatEval(refresh).metered(
            ttl - refreshBefore
          )).evalMap(
            updateState
          ).compile.drain
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

      asyncRefresh.compile.drain.start.map { fiber =>
        tmpCred -> (fiber.cancel: F[Unit])
      }
    }
  }
}
