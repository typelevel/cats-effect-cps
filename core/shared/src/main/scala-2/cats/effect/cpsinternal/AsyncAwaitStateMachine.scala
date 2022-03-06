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

package cats.effect.cpsinternal

import cats.effect.kernel.Async
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.kernel.syntax.all._
import cats.effect.std.Dispatcher
import cats.syntax.all._

abstract class AsyncAwaitStateMachine[F[_]](
    dispatcher: Dispatcher[F],
    callback: AsyncAwaitDsl.AwaitCallback[F])(
    implicit F: Async[F])
    extends Function1[AsyncAwaitDsl.AwaitOutcome[F], Unit] {

  type FF[A] = F[A]

  // FSM translated method
  //def apply(v1: AsyncAwaitDsl.AwaitOutcome[F]): Unit = ???

  // Resorting to mutation to track algebraic product effects (like WriterT),
  // since the information they carry would otherwise get lost on every dispatch.
  private[this] var summary: F[Unit] = F.unit

  private[this] var state$async: Int = 0

  /**
   * Retrieve the current value of the state variable
   */
  protected def state: Int = state$async

  /**
   * Assign `i` to the state variable
   */
  protected def state_=(s: Int): Unit = state$async = s

  protected def completeFailure(t: Throwable): Unit =
    callback(Left(t))

  protected def completeSuccess(value: AnyRef): Unit =
    callback(Right(F.as(summary, value)))

  protected def onComplete(f: F[AnyRef]): Unit = {
    dispatcher.unsafeRunAndForget {
      // Resorting to mutation to extract the "happy path" value from the monadic context,
      // as inspecting the Succeeded outcome using dispatcher is risky on algebraic sums,
      // such as OptionT, EitherT, ...
      var awaitedValue: Option[AnyRef] = None
      F.uncancelable { poll =>
        poll(summary *> f)
          .flatTap(r => F.delay { awaitedValue = Some(r) })
          .start
          .flatMap(fiber => poll(fiber.join).onCancel(fiber.cancel))
      }.flatMap {
        case Canceled() => F.delay(this(Left(F.canceled.asInstanceOf[F[AnyRef]])))
        case Errored(e) => F.delay(this(Left(F.raiseError(e))))
        case Succeeded(awaitOutcome) =>
          awaitedValue match {
            case Some(v) => F.delay(this(Right(awaitOutcome.void -> v)))
            case None => F.delay(this(Left(awaitOutcome)))
          }
      }
    }
  }

  protected def getCompleted(f: F[AnyRef]): AsyncAwaitDsl.AwaitOutcome[F] = {
    val _ = f
    null
  }

  protected def tryGet(awaitOutcome: AsyncAwaitDsl.AwaitOutcome[F]): AnyRef =
    awaitOutcome match {
      case Right((newSummary, value)) =>
        summary = newSummary
        value
      case Left(monadicStop) =>
        callback(Right(monadicStop))
        this // sentinel value to indicate the dispatch loop should exit.
    }

  def start(): Unit = {
    // Required to kickstart the async state machine.
    // `def apply` does not consult its argument when `state == 0`.
    apply(null)
  }

}
