package sqs4s.auth

import cats.data.Kleisli
import cats.effect.Resource
import org.http4s.client.Client
import org.http4s._

trait MockClient[F[_]] extends Client[F] {
  override def run(req: Request[F]): Resource[F, Response[F]] = ???

  override def fetch[A](req: Request[F])(f: Response[F] => F[A]): F[A] = ???

  override def fetch[A](req: F[Request[F]])(f: Response[F] => F[A]): F[A] = ???

  override def toKleisli[A](f: Response[F] => F[A]): Kleisli[F, Request[F], A] =
    ???

  override def toService[A](f: Response[F] => F[A]): Kleisli[F, Request[F], A] =
    ???

  override def toHttpApp: HttpApp[F] = ???

  override def toHttpService: HttpService[F] = ???

  override def stream(req: Request[F]): fs2.Stream[F, Response[F]] = ???

  override def streaming[A](req: Request[F])(f: Response[F] => fs2.Stream[F, A])
    : fs2.Stream[F, A] = ???

  override def streaming[A](req: F[Request[F]])(f: Response[F] => fs2.Stream[
    F,
    A
  ]): fs2.Stream[F, A] = ???

  override def expectOr[A](req: Request[F])(
    onError: Response[F] => F[Throwable]
  )(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def expect[A](req: Request[F])(implicit
  d: EntityDecoder[F, A]): F[A] = ???

  override def expectOr[A](req: F[Request[F]])(
    onError: Response[F] => F[Throwable]
  )(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def expect[A](req: F[Request[F]])(implicit
  d: EntityDecoder[F, A]): F[A] = ???

  override def expectOr[A](uri: Uri)(onError: Response[F] => F[Throwable])(
    implicit d: EntityDecoder[F, A]
  ): F[A] = ???

  override def expect[A](uri: Uri)(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def expectOr[A](s: String)(onError: Response[F] => F[Throwable])(
    implicit d: EntityDecoder[F, A]
  ): F[A] = ???

  override def expect[A](s: String)(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def expectOptionOr[A](req: Request[F])(
    onError: Response[F] => F[Throwable]
  )(implicit d: EntityDecoder[F, A]): F[Option[A]] = ???

  override def expectOption[A](req: Request[F])(implicit
  d: EntityDecoder[F, A]): F[Option[A]] = ???

  override def fetchAs[A](req: Request[F])(implicit
  d: EntityDecoder[F, A]): F[A] = ???

  override def fetchAs[A](req: F[Request[F]])(implicit
  d: EntityDecoder[F, A]): F[A] = ???

  override def status(req: Request[F]): F[Status] = ???

  override def status(req: F[Request[F]]): F[Status] = ???

  override def statusFromUri(uri: Uri): F[Status] = ???

  override def statusFromString(s: String): F[Status] = ???

  override def successful(req: Request[F]): F[Boolean] = ???

  override def successful(req: F[Request[F]]): F[Boolean] = ???

  override def prepAs[A](req: Request[F])(implicit
  d: EntityDecoder[F, A]): F[A] = ???

  override def get[A](uri: Uri)(f: Response[F] => F[A]): F[A] = ???

  override def get[A](s: String)(f: Response[F] => F[A]): F[A] = ???

  override def getAs[A](uri: Uri)(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def getAs[A](s: String)(implicit d: EntityDecoder[F, A]): F[A] = ???

  override def prepAs[T](req: F[Request[F]])(implicit
  d: EntityDecoder[F, T]): F[T] = ???
}
