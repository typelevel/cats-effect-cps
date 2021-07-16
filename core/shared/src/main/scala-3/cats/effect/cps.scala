/*
 * Copyright 2021 Typelevel
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

import _root_.cps.{async, await, CpsAsyncMonad, CpsAwaitable, CpsConcurrentEffectMonad, CpsMonad, CpsMonadPureMemoization}

import cats.effect.kernel.{Async, Concurrent, Fiber, Sync}
import cats.effect.kernel.syntax.all._

import scala.util.Try

object cps {

  inline def async[F[_]](using inline am: CpsMonad[F]): _root_.cps.macros.Async.InferAsyncArg[F] =
    new _root_.cps.macros.Async.InferAsyncArg[F]

  final implicit class AwaitSyntax[F[_], A](val self: F[A]) extends AnyVal {
    transparent inline def await(using inline am: CpsAwaitable[F]): A =
      _root_.cps.await[F, A](self)
  }

  implicit def catsEffectCpsMonadPureMemoization[F[_]](implicit F: Concurrent[F]): CpsMonadPureMemoization[F] =
    new CpsMonadPureMemoization[F] {
      def apply[A](fa: F[A]): F[F[A]] = F.memoize(fa)
    }

  // TODO we can actually provide some more gradient instances here
  implicit def catsEffectCpsConcurrentMonad[F[_]](implicit F: Async[F]): CpsConcurrentEffectMonad[F] =
    new CpsConcurrentEffectMonad[F] {

      type Spawned[A] = Fiber[F, Throwable, A]

      def spawnEffect[A](op: => F[A]): F[Spawned[A]] =
        F.defer(op).start

      def join[A](op: Spawned[A]): F[A] =
        op.joinWithNever

      def tryCancel[A](op: Spawned[A]): F[Unit] =
        op.cancel

      override def delay[A](x: => A): F[A] =
        Sync[F].delay(x)

      override def flatDelay[A](x: => F[A]): F[A] =
        Sync[F].defer(x)

      def adoptCallbackStyle[A](source: (Try[A] => Unit) => Unit): F[A] =
        F.async_(cb => source(t => cb(t.toEither)))

      def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)

      def map[A, B](fa: F[A])(f: A => B): F[B] = F.map(fa)(f)

      def pure[A](a: A): F[A] = F.pure(a)

      def error[A](e: Throwable): F[A] = F.raiseError(e)

      def flatMapTry[A,B](fa: F[A])(f: Try[A] => F[B]): F[B] =
        F.flatMap(F.attempt(fa))(e => f(e.toTry))
    }
}
