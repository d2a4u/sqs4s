package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import org.http4s.{Method, Request, Uri}
import sqs4s.api.AwsAuth
import sqs4s.internal.aws4.common._
import sqs4s.internal.models.CReq

case class SignedRequest[F[_]: Sync: Clock](
  params: List[(String, String)],
  request: Request[F],
  url: Uri,
  auth: AwsAuth) {
  def render: F[Request[F]] = {
    for {
      uriWithQueries <- params
        .foldLeft(url) {
          case (u, (key, value)) =>
            u.withQueryParam(key, value)
        }
        .pure[F]
      req <- request
        .withUri(uriWithQueries)
        .withHostHeader(uriWithQueries)
        .withExpiresHeaderF[F]()
        .flatMap(_.withXAmzDateHeaderF[F])
      creq = CReq[F](req)
      authed <- creq
        .toAuthorizedRequest(auth.accessKey, auth.secretKey, auth.region, "sqs")
    } yield authed
  }
}

object SignedRequest {
  def post[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    auth: AwsAuth
  ) =
    SignedRequest[F](params, Request[F](method = Method.POST), url, auth)

  def get[F[_]: Sync: Clock](
    params: List[(String, String)],
    url: Uri,
    auth: AwsAuth
  ) =
    SignedRequest[F](params, Request[F](method = Method.GET), url, auth)
}
