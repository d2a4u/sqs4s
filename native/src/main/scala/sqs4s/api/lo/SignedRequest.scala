package sqs4s.api.lo

import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.syntax.all._
import org.http4s.{Method, Request, Uri}
import org.typelevel.log4cats.Logger
import sqs4s.auth.{Credential, Credentials, TemporarySecurityCredential}
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

final case class SignedRequest[F[_]: Sync](
  params: List[(String, String)],
  request: Request[F],
  uri: Uri,
  credentials: Credentials[F],
  region: String
) {
  def render(logger: Logger[F]): F[Request[F]] = {
    for {
      credential <- credentials.get
      time <- Sync[F].realTime
      millis = time.toMillis
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
            millis,
            logger
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
        (
          ("SecurityToken" -> cred.sessionToken) ::
            ("AWSAccessKeyId" -> cred.accessKey) ::
            params
        ).sortBy {
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
  def post[F[_]: Sync](
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

  def get[F[_]: Sync](
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
