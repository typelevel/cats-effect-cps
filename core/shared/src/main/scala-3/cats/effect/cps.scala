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

import _root_.cps.{async, await, CpsAsyncMonad, CpsAwaitable, CpsConcurrentEffectMonad, CpsMonad, CpsMonadContext, CpsMonadInstanceContext, CpsMonadMemoization}

import cats.effect.kernel.{Async, Concurrent, Fiber, Sync}
import cats.effect.kernel.syntax.all._

import scala.annotation.implicitNotFound
import scala.util.Try

object cps {

  transparent inline def async[F[_]](using CpsMonad[F]) = _root_.cps.macros.Async.async[F]

  extension [F[_], A](self: F[A])(using CpsAwaitable[F], CpsMonadContext[F]) {
    transparent inline def await: A = _root_.cps.await[F, A, F](self)
  }

  @implicitNotFound("automatic coloring is disabled")
  implicit def automaticColoringTag1[F[_]:Concurrent]: _root_.cps.automaticColoring.AutomaticColoringTag[F] = ???
  implicit def automaticColoringTag2[F[_]:Concurrent]: _root_.cps.automaticColoring.AutomaticColoringTag[F] = ???

  // TODO we can actually provide some more gradient instances here
  implicit def catsEffectCpsConcurrentMonad[F[_]](implicit F: Async[F]): CpsConcurrentEffectMonad[F] with CpsMonadInstanceContext[F] =
    new CpsConcurrentEffectMonad[F] with CpsMonadInstanceContext[F] {

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
