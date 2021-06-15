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

package cats.effect.cps

import scala.annotation.compileTimeOnly
import scala.reflect.macros.blackbox

import cats.effect.kernel.Async
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.Outcome.{Canceled, Errored, Succeeded}
import cats.effect.std.Dispatcher
import cats.syntax.all._

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
 * import dsl._
 *
 * val io: IO[Int] = ???
 * async[IO] { io.await + io.await }
 * }}}
 *
 * The code is transformed at compile time into a state machine
 * that sequentially calls upon a [[Dispatcher]] every time it reaches
 * an "await" block.
 */
object dsl {

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
    def apply[A](body: => A)(implicit F: Async[F]): F[A] = macro AsyncAwaitDsl.asyncImpl[F, A]
  }
}

object AsyncAwaitDsl {

  type AwaitCallback[F[_]] = Either[Throwable, F[AnyRef]] => Unit

  // Outcome of an await block. Either a failed algebraic computation,
  // or a successful value accompanied by a "summary" computation.
  //
  // Allows to short-circuit the async/await state machine when relevant
  // (think OptionT.none) and track algebraic information that may otherwise
  // get lost during Dispatcher#unsafeRun calls (WriterT/IorT logs).
  type AwaitOutcome[F[_]] = Either[F[AnyRef], (F[Unit], AnyRef)]

  def awaitImpl[F[_], A](c: blackbox.Context): c.Expr[A] = {
    import c.universe._
    c.Expr(q"""_root_.cats.effect.cps.dsl._await(${c.prefix}.self)""")
  }

  def asyncImpl[F[_], A](
      c: blackbox.Context)(
      body: c.Expr[A])(
      F: c.Expr[Async[F]])(
      implicit A: c.universe.WeakTypeTag[A]): c.Expr[F[A]] = {
    import c.universe._
    if (!c.compilerSettings.contains("-Xasync")) {
      c.abort(
        c.macroApplication.pos,
        "async/await syntax requires the compiler option -Xasync (supported only by Scala 2.12.12+ / 2.13.3+)")
    } else
      try {
        val awaitSym = typeOf[dsl.type].decl(TermName("_await"))

        val stateMachineSymbol = symbolOf[AsyncAwaitStateMachine[Any]]

        // Iterating through all subtrees in the scope but stopping at anything that extends
        // AsyncAwaitStateMachine (as it indicates the macro-expansion of a nested async region)
        def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.filter(_ match {
          case ClassDef(_, _, _, Template(List(parent), _, _)) if parent.symbol == stateMachineSymbol =>
            false
          case _ =>
            true
        }).flatMap(rec(_))

        // Checking each local `await` call to ensure that it matches the `F` in `async[F]`
        val effect = F.actualType.typeArgs.head

        rec(body.tree).foreach {
          case tt @ c.universe.Apply(TypeApply(_, List(awaitEffect, _)), fun :: Nil) if tt.symbol == awaitSym =>
            // awaitEffect is the F in `await[F, A](fa)`
            if (!(awaitEffect.tpe <:< effect)){
              c.abort(fun.pos, s"Expected await to be called on ${effect}, but was called on ${fun.tpe.dealias}")
            }
          case _ => ()
        }

        def mark(t: DefDef): Tree = {
          c.internal
            .asInstanceOf[{
              def markForAsyncTransform(
                  owner: Symbol,
                  method: DefDef,
                  awaitSymbol: Symbol,
                  config: Map[String, AnyRef])
                  : DefDef
            }]
            .markForAsyncTransform(
              c.internal.enclosingOwner,
              t,
              awaitSym,
              Map.empty)
        }

        val name = TypeName("stateMachine$async")

        // format: off
        val tree = q"""
          final class $name(dispatcher: _root_.cats.effect.std.Dispatcher[$effect], callback: _root_.cats.effect.cps.AsyncAwaitDsl.AwaitCallback[$effect]) extends _root_.cats.effect.cps.AsyncAwaitStateMachine(dispatcher, callback)(${F}) {
            ${mark(q"""override def apply(tr$$async: _root_.cats.effect.cps.AsyncAwaitDsl.AwaitOutcome[$effect]): _root_.scala.Unit = $body""")}
          }
          ${F}.flatten {
            _root_.cats.effect.std.Dispatcher[$effect].use { dispatcher =>
              ${F}.async_[$name#FF[AnyRef]](cb => new $name(dispatcher, cb).start())
            }
          }.asInstanceOf[$name#FF[$A]]
        """
        // format: on

        c.Expr[F[A]](tree)
      } catch {
        case e: ReflectiveOperationException =>
          c.abort(
            c.macroApplication.pos,
            "-Xasync is provided as a Scala compiler option, but the async macro is unable to call c.internal.markForAsyncTransform. " + e
              .getClass
              .getName + " " + e.getMessage
          )
      }
  }

}

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
