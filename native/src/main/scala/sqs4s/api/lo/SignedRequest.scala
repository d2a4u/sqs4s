package sqs4s.api.lo

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.AwsAuth
import sqs4s.auth.{BasicCredential, Credential, TemporarySecurityCredential}
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

case class SignedRequest[F[_]: Sync: Clock](
  params: List[(String, String)],
  request: Request[F],
  url: Uri,
  credential: Credential[F],
  region: String
) {
  def render: F[Request[F]] = {
    for {
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      uriWithQueries <-
        params
          .foldLeft(url) {
            case (u, (key, value)) =>
              u.withQueryParam(key, value)
          }
          .pure[F]
      req <-
        request
          .withUri(uriWithQueries)
          .withHostHeader(uriWithQueries)
          .withExpiresHeaderF[F]()
          .flatMap(_.withXAmzDateHeaderF[F](millis))
      creq = CReq[F](req)
      accessKey <- credential.accessKey
      secretKey <- credential.secretKey
      authed <-
        creq
          .toAuthorizedRequest(
            accessKey,
            secretKey,
            region,
            "sqs",
            millis
          )
    } yield authed
  }
}

object SignedRequest {

  def apply[F[_]: Sync: Clock](
    params: List[(String, String)],
    request: Request[F],
    url: Uri,
    auth: AwsAuth
  ): SignedRequest[F] =
    SignedRequest[F](
      params,
      request,
      url,
      BasicCredential[F](auth.accessKey, auth.secretKey),
      auth.region
    )

  @deprecated("use Credential instead", "1.1.0")
  def post[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    auth: AwsAuth
  ): SignedRequest[F] = {
    val sortedParams = params.sortBy {
      case (key, _) => key
    }
    SignedRequest[F](sortedParams, Request[F](method = Method.POST), url, auth)
  }

  @deprecated("use Credential instead", "1.1.0")
  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    auth: AwsAuth
  ): SignedRequest[F] = {
    val sortedParams = params.sortBy {
      case (key, _) => key
    }
    SignedRequest[F](sortedParams, Request[F](method = Method.GET), url, auth)
  }

  def post[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    credential: Credential[F],
    region: String
  ): F[SignedRequest[F]] =
    withTempCredParams(params, credential).map { pr =>
      SignedRequest[F](
        pr,
        Request[F](method = Method.POST),
        url,
        credential,
        region
      )
    }

  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    credential: Credential[F],
    region: String
  ): F[SignedRequest[F]] =
    withTempCredParams(params, credential).map { pr =>
      SignedRequest[F](
        pr,
        Request[F](method = Method.GET),
        url,
        credential,
        region
      )
    }

  private def withTempCredParams[F[_]: Monad](
    params: List[(String, String)],
    credential: Credential[F]
  ): F[List[(String, String)]] = {
    credential match {
      case cred: TemporarySecurityCredential[F] =>
        for {
          st <- cred.sessionToken
          ak <- cred.accessKey
        } yield {
          (params ++ List(
            "SecurityToken" -> st,
            "AWSAccessKeyId" -> ak
          )).sortBy {
            case (key, _) => key
          }
        }

      case _ =>
        params.sortBy {
          case (key, _) => key
        }.pure[F]
    }
  }
}
