package sqs4s.api.lo

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.AwsAuth
import sqs4s.auth.{BasicCredProvider, CredProvider, TemporaryCredProvider}
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

case class SignedRequest[F[_]: Sync: Clock](
  params: List[(String, String)],
  request: Request[F],
  url: Uri,
  credProvider: CredProvider[F],
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
      accessKey <- credProvider.accessKey
      secretKey <- credProvider.secretKey
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
      BasicCredProvider[F](auth.accessKey, auth.secretKey),
      auth.region
    )

  @deprecated("use credProvider instead", "1.1.0")
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

  @deprecated("use credProvider instead", "1.1.0")
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
    credProvider: CredProvider[F],
    region: String
  ): F[SignedRequest[F]] = {
    withTempCredParamsF(params, credProvider).map { params =>
      SignedRequest[F](
        params,
        Request[F](method = Method.POST),
        url,
        credProvider,
        region
      )
    }
  }

  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    credProvider: CredProvider[F],
    region: String
  ): F[SignedRequest[F]] = {
    withTempCredParamsF(params, credProvider).map { params =>
      SignedRequest[F](
        params,
        Request[F](method = Method.GET),
        url,
        credProvider,
        region
      )
    }
  }

  private def withTempCredParamsF[F[_]: Monad](
    params: List[(String, String)],
    credProvider: CredProvider[F]
  ) = {
    credProvider match {
      case provider: TemporaryCredProvider[F] =>
        for {
          token <- provider.sessionToken
          accessKey <- provider.accessKey
        } yield {
          (params ++ List(
            "SecurityToken" -> token,
            "AWSAccessKeyId" -> accessKey
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
