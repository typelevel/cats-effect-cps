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

import scala.annotation.compileTimeOnly

import cats.effect.cpsinternal.AsyncAwaitDsl

/**
 * WARNING: This construct currently only works on scala 2 (2.12.12+ / 2.13.3+),
 * relies on an experimental compiler feature enabled by the -Xasync
 * scalac option, and should absolutely be considered unstable with
 * regards to backward compatibility guarantees (be that source or binary).
 *
 * Partially applied construct allowing for async/await semantics,
 * popularised in other programming languages.
 *
 * {{{
 * import cats.effect.cps._
 *
 * val io: IO[Int] = ???
 * async[IO] { io.await + io.await }
 * }}}
 *
 * The code is transformed at compile time into a state machine
 * that sequentially calls upon a [[Dispatcher]] every time it reaches
 * an "await" block.
 */
object cps {

  implicit class AwaitSyntax[F[_], A](val self: F[A]) extends AnyVal {

    /**
     * Non-blocking await the on result of `awaitable`. This may only be used directly within an enclosing `async` block.
     *
     * Internally, this transforms the remainder of the code in enclosing `async` block into a callback
     * that triggers upon a successful computation outcome. It does *not* block a thread.
     */
    def await: A = macro AsyncAwaitDsl.awaitImpl[F, A]
  }

  @compileTimeOnly("[async] `await` must be enclosed in an `async` block")
  def _await[F[_], A](self: F[A]): A = ???

  /**
   * Run the block of code `body` asynchronously. `body` may contain calls to `await` when the results of
   * a `F` are needed; this is translated into non-blocking code.
   */
  def async[F[_]]: PartiallyAppliedAsync[F] = new PartiallyAppliedAsync[F]

  final class PartiallyAppliedAsync[F0[_]] {
    type F[A] = F0[A]

    // don't convert this into an import; it hits a bug in Scala 2.12
    def apply[A](body: => A)(implicit F: cats.effect.kernel.Async[F]): F[A] =
      macro AsyncAwaitDsl.asyncImpl[F, A]
  }
}

