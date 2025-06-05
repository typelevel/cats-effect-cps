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

import scala.reflect.macros.blackbox

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
    c.Expr(q"""_root_.cats.effect.cps._await(${c.prefix}.self)""")
  }

  def asyncImpl[F[_], A](
      c: blackbox.Context)(
      body: c.Expr[A])(
      F: c.Expr[Async[F]]): c.Expr[F[A]] = {
    import c.universe._
    if (!c.compilerSettings.contains("-Xasync")) {
      c.abort(
        c.macroApplication.pos,
        "async/await syntax requires the compiler option -Xasync (supported only by Scala 2.12.12+ / 2.13.3+)")
    } else
      try {
        val awaitSym = typeOf[cats.effect.cps.type].decl(TermName("_await"))

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
              c.abort(fun.pos, s"expected await to be called on ${effect}, but was called on ${fun.tpe.dealias}")
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
          final class $name(dispatcher: _root_.cats.effect.std.Dispatcher[$effect], callback: _root_.cats.effect.cpsinternal.AsyncAwaitDsl.AwaitCallback[$effect]) extends _root_.cats.effect.cpsinternal.AsyncAwaitStateMachine(dispatcher, callback)(${F}) {
            ${mark(q"""override def apply(tr$$async: _root_.cats.effect.cpsinternal.AsyncAwaitDsl.AwaitOutcome[$effect]): _root_.scala.Unit = $body""")}
          }
          ${F}.flatten {
            _root_.cats.effect.std.Dispatcher.sequential[$effect].use { dispatcher =>
              ${F}.async[$name#FF[AnyRef]](cb => ${F}.delay { new $name(dispatcher, cb).start(); Some(${F}.unit) })
            }
          }.asInstanceOf[${c.macroApplication.tpe}]
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
