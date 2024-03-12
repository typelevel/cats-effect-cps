/*
 * Copyright 2021-2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect

import _root_.cps._
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.{Async, Concurrent, Fiber, Sync}
import cats.{Monad, MonadThrow}

import scala.util.Try

object cps {

  transparent inline def async[F[_]](using CpsMonad[F]) =
    _root_.cps.macros.Async.async[F]

  extension [F[_], A](self: F[A])(using CpsMonadContext[F]) {
    transparent inline def await: A = _root_.cps.await[F, A, F](self)
  }

  abstract class MonadCpsMonad[F[_]: Monad] extends CpsMonad[F] {
    override def pure[T](t: T): F[T] =
      Monad[F].pure(t)

    override def map[A, B](fa: F[A])(f: A => B): F[B] =
      Monad[F].map(fa)(f)

    override def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] =
      Monad[F].flatMap(fa)(f)
  }

  abstract class MonadThrowCpsMonad[F[_]: MonadThrow]
      extends MonadCpsMonad[F]
      with CpsTryMonad[F] {
    override def error[A](e: Throwable): F[A] =
      MonadThrow[F].raiseError(e)

    override def flatMapTry[A, B](fa: F[A])(f: Try[A] => F[B]): F[B] =
      MonadThrow[F].flatMap(MonadThrow[F].attempt(fa))(e => f(e.toTry))
  }

  abstract class SyncCpsMonad[F[_]: Sync]
      extends MonadThrowCpsMonad[F]
      with CpsEffectMonad[F] {
    override def delay[T](x: => T): F[T] =
      Sync[F].delay(x)

    override def flatDelay[T](x: => F[T]): F[T] =
      Sync[F].defer(x)
  }

  abstract class AsyncCpsMonad[F[_]: Async]
      extends SyncCpsMonad[F]
      with CpsAsyncEffectMonad[F] {
    override def adoptCallbackStyle[A](source: (Try[A] => Unit) => Unit): F[A] =
      Async[F].async_(f => source(e => f(e.toEither)))
  }

  abstract class ConcurrentCpsMonad[F[_]: Async: Concurrent]
      extends AsyncCpsMonad[F]
      with CpsConcurrentEffectMonad[F] {
    override type Spawned[A] = Fiber[F, Throwable, A]

    override def spawnEffect[A](op: => F[A]): F[Spawned[A]] =
      Sync[F].defer(op).start

    override def join[A](op: Spawned[A]): F[A] =
      op.joinWithNever

    override def tryCancel[A](op: Spawned[A]): F[Unit] =
      op.cancel
  }

  implicit def catsMonadCps[F[_]](implicit F: Monad[F]): CpsMonad[F] = new MonadCpsMonad[F]
    with CpsPureMonadInstanceContext[F]

  implicit def catsMonadThrowCps[F[_]](implicit F: Async[F]): CpsTryMonad[F] = new MonadThrowCpsMonad[F]
    with CpsTryMonadInstanceContext[F]

  implicit def catsAsyncCps[F[_]](implicit F: Async[F]): CpsAsyncEffectMonad[F] = new AsyncCpsMonad[F]
    with CpsAsyncEffectMonadInstanceContext[F]

  implicit def catsConcurrentCps[F[_]](implicit F: Async[F], concurrent: Concurrent[F]): CpsConcurrentEffectMonad[F] =
    new ConcurrentCpsMonad[F] with CpsConcurrentEffectMonadInstanceContext[F]

}
