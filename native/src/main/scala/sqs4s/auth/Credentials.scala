package sqs4s.auth

import cats.{Applicative, ApplicativeError}
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.all._
import fs2._
import fs2.io.file
import org.http4s.Method.{GET, PUT}
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.client.middleware.FollowRedirect
import org.http4s.scalaxml._
import org.http4s.syntax.all._
import org.http4s._
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.auth.errors._

import java.io.IOException
import java.nio.file.Paths
import java.time.Instant
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import scala.xml.Elem

trait Credentials[F[_]] {
  def get: F[Credential]
}

sealed trait Credential {
  def accessKey: String
  def secretKey: String
}

final case class BasicCredential(accessKey: String, secretKey: String)
    extends Credential

final case class TemporarySecurityCredential(
  accessKey: String,
  secretKey: String,
  sessionToken: String
) extends Credential

/** Implementation which follows AWS doc
  * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
  */
object Credentials {
  def instance[F[_]](credF: F[Credential]): Credentials[F] =
    new Credentials[F] {
      def get: F[Credential] = credF
    }

  def of[F[_]: ApplicativeError[*[_], Throwable]]: Resource[F, Credentials[F]] =
    Resource.eval(NoValidAuthMethodError.raiseError[F, Credentials[F]])

  def basic[F[_]: Applicative](
    accessKey: String,
    secretKey: String
  ): Credentials[F] =
    Credentials.instance[F](
      BasicCredential(accessKey, secretKey).pure[F].widen
    )

  def temporal[F[_]: Applicative](
    accessKey: String,
    secretKey: String,
    sessionToken: String
  ): Credentials[F] =
    Credentials.instance[F](
      TemporarySecurityCredential(accessKey, secretKey, sessionToken).pure[F].widen
    )

  /** Load Credentials in this order:
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
  def chain[F[_]: Concurrent: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes,
    allowRedirect: Boolean = true
  ): Resource[F, Credentials[F]] = {
    val env = envVar[F]
    val sys = sysProp[F]
    val container =
      containerMetadata[F](client, ttl, refreshBefore, allowRedirect)
    val instance =
      instanceMetadata[F](client, ttl, refreshBefore, allowRedirect)

    val tryAllInOrder =
      env orElse sys orElse container orElse instance

    tryAllInOrder.handleErrorWith { _ =>
      Resource.eval(NoValidAuthMethodError.raiseError)
    }
  }

  def all[F[_]: Concurrent: ContextShift: Timer](
    client: Client[F],
    blocker: Blocker,
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes,
    allowRedirect: Boolean = true
  ): Resource[F, Credentials[F]] = {
    val sts = stsAssumeRoleWithWebIdentitySession[F](
      client,
      blocker,
      ttl,
      refreshBefore,
      allowRedirect
    )
    chain[F](client, ttl, refreshBefore, allowRedirect) orElse sts
  }

  def envVar[F[_]: Sync]: Resource[F, Credentials[F]] =
    Resource.eval {
      val ACCESS_KEY_ENV_VAR = "AWS_ACCESS_KEY_ID"
      val ALTERNATE_ACCESS_KEY_ENV_VAR = "AWS_ACCESS_KEY"

      val SECRET_KEY_ENV_VAR = "AWS_SECRET_KEY"
      val ALTERNATE_SECRET_KEY_ENV_VAR = "AWS_SECRET_ACCESS_KEY"

      val AWS_SESSION_TOKEN_ENV_VAR = "AWS_SESSION_TOKEN"
      val accessKeyF =
        SystemF.env[F](ACCESS_KEY_ENV_VAR) orElse SystemF.env[F](
          ALTERNATE_ACCESS_KEY_ENV_VAR
        )

      val secretKeyF =
        SystemF.env[F](SECRET_KEY_ENV_VAR) orElse SystemF.env[F](
          ALTERNATE_SECRET_KEY_ENV_VAR
        )

      val optSessionTokenF = SystemF.envOpt[F](AWS_SESSION_TOKEN_ENV_VAR)

      for {
        accessKey <- accessKeyF
        secretKey <- secretKeyF
        optSessionToken <- optSessionTokenF
      } yield {
        optSessionToken.fold(
          Credentials.basic[F](
            accessKey,
            secretKey
          )
        ) { token =>
          Credentials.temporal[F](
            accessKey,
            secretKey,
            token
          )
        }
      }
    }

  def sysProp[F[_]: Sync]: Resource[F, Credentials[F]] =
    Resource.eval {
      val ACCESS_KEY_SYSTEM_PROPERTY = "aws.accessKeyId"
      val SECRET_KEY_SYSTEM_PROPERTY = "aws.secretKey"
      val SESSION_TOKEN_SYSTEM_PROPERTY = "aws.sessionToken"
      val accessKeyF =
        SystemF.prop[F](ACCESS_KEY_SYSTEM_PROPERTY)
      val secretKeyF =
        SystemF.prop[F](SECRET_KEY_SYSTEM_PROPERTY)
      val optSessionTokenF =
        SystemF.propOpt[F](SESSION_TOKEN_SYSTEM_PROPERTY)

      for {
        accessKey <- accessKeyF
        secretKey <- secretKeyF
        optSessionToken <- optSessionTokenF
      } yield {
        optSessionToken.fold(
          Credentials.basic[F](
            accessKey,
            secretKey
          )
        ) { token =>
          Credentials.temporal[F](
            accessKey,
            secretKey,
            token
          )
        }
      }
    }

  def containerMetadata[F[_]: Concurrent: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes,
    allowRedirect: Boolean = true
  ): Resource[F, Credentials[F]] = {
    val dsl = new Http4sClientDsl[F] {}
    import dsl._
    val RELATIVE_URI_ENV_VAR = "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"
    val httpClient =
      if (allowRedirect) {
        FollowRedirect[F](10)(client)
      } else {
        client
      }

    val uriF = SystemF.env[F](RELATIVE_URI_ENV_VAR).flatMap {
      path =>
        //Static address to retrieve cred from within container per API doc: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html
        Sync[F].fromEither(Uri.fromString(s"http://169.254.170.2$path"))
    }

    val refresh: F[CredentialResponse] =
      for {
        uri <- uriF
        cred <- httpClient.expectOr[CredentialResponse](GET(uri))(onError)
      } yield cred

    temporaryCredentials[F](refresh, ttl, refreshBefore)
  }

  /** Create a resource of temporary credential, credential is automatically
    * retrieved from EC2 instance metadata: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html#instance-metadata-security-credentials
    *
    * @param client        HTTP client
    * @param ttl           session token's time to live
    * @param refreshBefore a small duration to refresh the token before it expires
    * @tparam F an effect which represents the side effects
    * @return a Resource of TemporarySecurityCredential
    */
  def instanceMetadata[F[_]: Concurrent: Timer](
    client: Client[F],
    ttl: FiniteDuration = 6.hours,
    refreshBefore: FiniteDuration = 5.minutes,
    allowRedirect: Boolean = true
  ): Resource[F, Credentials[F]] = {
    val dsl = new Http4sClientDsl[F] {}
    import dsl._

    // Static address to retrieve cred per API doc: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html#instance-metadata-security-credentials
    // and AWS SDK: https://github.com/aws/aws-sdk-java/blob/542606ddd5ce6f0c3570ef7488c4f5479c6425bb/aws-java-sdk-core/src/main/java/com/amazonaws/util/EC2MetadataUtils.java#L73
    val awsLinkLocal = uri"http://169.254.169.254"
    val tokenEndpoint = awsLinkLocal / "latest" / "api" / "token"
    val credsEndpoint =
      awsLinkLocal / "latest" / "meta-data" / "iam" / "security-credentials"
    val ttlHeader = "X-aws-ec2-metadata-token-ttl-seconds"
    val tokenHeader = "X-aws-ec2-metadata-token"
    val httpClient =
      if (allowRedirect) {
        FollowRedirect[F](10)(client)
      } else {
        client
      }

    val refresh: F[CredentialResponse] =
      for {
        token <- httpClient.expectOr[String](PUT(tokenEndpoint))(onError)
        creds <- httpClient.expectOr[List[CredentialResponse]](
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

    temporaryCredentials[F](refresh, ttl, refreshBefore)
  }

  def stsAssumeRoleWithWebIdentitySession[
    F[_]: Concurrent: ContextShift: Timer
  ](
    client: Client[F],
    blocker: Blocker,
    ttl: FiniteDuration = 3600.seconds,
    refreshBefore: FiniteDuration = 5.minutes,
    allowRedirect: Boolean = true
  ): Resource[F, Credentials[F]] = {
    val stsEndpoint = uri"https://sts.amazonaws.com/"
    val httpClient =
      if (allowRedirect) {
        FollowRedirect[F](10)(client)
      } else {
        client
      }

    def refresh(
      roleArn: String,
      roleSessionName: String,
      webIdTokenFile: String
    ): F[CredentialResponse] = {
      def uri(webIdToken: String) = stsEndpoint.withQueryParams(
        Map(
          "Action" -> "AssumeRoleWithWebIdentity",
          "DurationSeconds" -> ttl.toSeconds.toString,
          "ProviderId" -> "www.amazon.com",
          "RoleSessionName" -> roleSessionName,
          "RoleArn" -> roleArn,
          "WebIdentityToken" -> webIdToken,
          "Version" -> "2011-06-15"
        )
      )

      def parseResponse(xml: Elem): Either[Throwable, CredentialResponse] = {
        val credOpt =
          for {
            node <- (xml \\ "Credentials").headOption
            st <- Option((node \ "SessionToken").text)
            sak <- Option((node \ "SecretAccessKey").text)
            expStr <- Option((node \ "Expiration").text)
            exp <- Try(Instant.parse(expStr)).toOption
            akid <- Option((node \ "AccessKeyId").text)
          } yield CredentialResponse(akid, sak, st, None, exp)

        lazy val errOpt =
          for {
            node <- (xml \\ "Credentials").headOption
            st <- Option((node \ "SessionToken").text)
            sak <- Option((node \ "SecretAccessKey").text)
            expStr <- Option((node \ "Expiration").text)
            exp <- Try(Instant.parse(expStr)).toOption
            akid <- Option((node \ "AccessKeyId").text)
          } yield CredentialResponse(akid, sak, st, None, exp)

        Either.fromOption(credOpt, )
      }

      for {
        token <- file.readAll[F](
          Paths.get(webIdTokenFile),
          blocker,
          1024
        ).through(text.utf8Decode).through(text.lines).compile.toList
        req = Request[F](method = Method.GET).withUri(uri(token.mkString))
        resp <- httpClient.expectOr[Elem](req)(onError)
        cred <-
          Sync[F].fromOption(
            parseResponse(resp),
            UnexpectedResponseError("AssumeRoleWithWebIdentityResponse", resp)
          )
      } yield cred
    }

    val AWS_ROLE_ARN_ENV_VAR = "AWS_ROLE_ARN"
    val AWS_ROLE_SESSION_NAME_ENV_VAR = "AWS_ROLE_SESSION_NAME"
    val AWS_WEB_IDENTITY_TOKEN_FILE_ENV_VAR = "AWS_WEB_IDENTITY_TOKEN_FILE"

    val SDK_DEFAULT_BASE_DELAY = 100
    val SDK_DEFAULT_THROTTLED_BASE_DELAY = 500

    val roleArnF =
      SystemF.env[F](AWS_ROLE_ARN_ENV_VAR)
    val roleSessionNameF =
      SystemF.envOpt[F](AWS_ROLE_SESSION_NAME_ENV_VAR).flatMap {
        case Some(str) => str.pure[F]
        case None =>
          Timer[F].clock.realTime(MILLISECONDS).map("aws-sdk-java-" + _)
      }
    val webIdTokenFileF = SystemF.env[F](AWS_WEB_IDENTITY_TOKEN_FILE_ENV_VAR)

    // TODO: add and fix RetriableServerError
    val credF = for {
      roleArn <- roleArnF
      roleSessionName <- roleSessionNameF
      webIdTokenFile <- webIdTokenFileF
    } yield {
      temporaryCredentials[F](
        refresh(roleArn, roleSessionName, webIdTokenFile),
        ttl,
        refreshBefore
      )
    }

    Resource.eval(credF).flatten
  }

  private def onError[F[_]: Applicative]: Response[F] => F[Throwable] = {
    resp =>
      {
        if (resp.status.responseClass == Status.ServerError) {
          RetriableServerError.pure[F].widen
        } else {
          UnknownAuthError(resp.status).pure[F].widen
        }
      }
  }

  // https://github.com/aws/aws-sdk-java/blob/79b4564b3f2496040e317abd057c1b56802c683a/aws-java-sdk-core/src/main/java/com/amazonaws/retry/PredefinedBackoffStrategies.java#L129
  private def retryBackOff[F[_]: Timer: RaiseThrowable, T](
    f: F[T],
    base: FiniteDuration
  ): F[T] = {
    val cond: Throwable => Boolean = {
      case _: TimeoutException => true
      case _: IOException => true

    }
    Stream.retry[F, T](f, base, _ * 2, 30, cond).compile.lastOrError
  }

  private def temporaryCredentials[F[_]: Concurrent: Timer](
    refresh: F[CredentialResponse],
    ttl: FiniteDuration,
    refreshBefore: FiniteDuration
  ) =
    Resource.eval {
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
