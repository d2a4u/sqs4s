package sqs4s.api.lo

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.AwsAuth
import sqs4s.auth.{Credential, Credentials, TemporarySecurityCredential}
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

case class SignedRequest[F[_]: Sync: Clock](
  params: List[(String, String)],
  request: Request[F],
  uri: Uri,
  credentials: Credentials[F],
  region: String
) {
  def render: F[Request[F]] = {
    for {
      credential <- credentials.get
      millis <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      fullUri = buildUri(uri, params, credential)
      req <-
        request
          .withUri(fullUri)
          .withHostHeader(fullUri)
          .withExpiresHeaderF[F]()
          .flatMap(_.withXAmzDateHeaderF[F](millis))
      creq = CReq[F](req)
      authed <-
        creq
          .toAuthorizedRequest(
            credential.accessKey,
            credential.secretKey,
            region,
            "sqs",
            millis
          )
    } yield authed
  }

  private def buildUri(
    uri: Uri,
    params: List[(String, String)],
    credential: Credential
  ): Uri = {
    val paramsWithCred = credential match {
      case cred: TemporarySecurityCredential =>
        (params ++ List(
          "SecurityToken" -> cred.sessionToken,
          "AWSAccessKeyId" -> cred.accessKey
        )).sortBy {
          case (key, _) => key
        }

      case _ =>
        params.sortBy {
          case (key, _) => key
        }
    }
    paramsWithCred.foldLeft(uri) {
      case (u, (key, value)) =>
        u.withQueryParam(key, value)
    }
  }
}

object SignedRequest {

  def apply[F[_]: Sync: Clock](
    params: List[(String, String)],
    request: Request[F],
    uri: Uri,
    auth: AwsAuth
  ): SignedRequest[F] =
    SignedRequest[F](
      params,
      request,
      uri,
      Credentials.basic[F](auth.accessKey, auth.secretKey),
      auth.region
    )

  @deprecated("use Credential instead", "1.1.0")
  def post[F[_]: Sync: Clock](
    params: List[(String, String)],
    uri: Uri,
    auth: AwsAuth
  ): SignedRequest[F] = {
    val sortedParams = params.sortBy {
      case (key, _) => key
    }
    SignedRequest[F](sortedParams, Request[F](method = Method.POST), uri, auth)
  }

  @deprecated("use Credential instead", "1.1.0")
  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    uri: Uri,
    auth: AwsAuth
  ): SignedRequest[F] = {
    val sortedParams = params.sortBy {
      case (key, _) => key
    }
    SignedRequest[F](sortedParams, Request[F](method = Method.GET), uri, auth)
  }

  def post[F[_]: Sync: Clock](
    params: List[(String, String)],
    uri: Uri,
    credentials: Credentials[F],
    region: String
  ): SignedRequest[F] =
    SignedRequest[F](
      params,
      Request[F](method = Method.POST),
      uri,
      credentials,
      region
    )

  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    uri: Uri,
    credentials: Credentials[F],
    region: String
  ): SignedRequest[F] =
    SignedRequest[F](
      params,
      Request[F](method = Method.GET),
      uri,
      credentials,
      region
    )
}
