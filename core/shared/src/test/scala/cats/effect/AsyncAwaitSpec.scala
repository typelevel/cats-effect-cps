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

import cats.syntax.all._
import cats.data.{Kleisli, OptionT, WriterT}
import cats.effect.testing.specs2.CatsEffect

import org.specs2.mutable.Specification
import org.specs2.execute._, Typecheck._

import scala.concurrent.duration._

import cps._

class AsyncAwaitSpec extends Specification with CatsEffect {

  "async[IO]" should {

    "work on success" in {

      val io = IO.sleep(100.millis) >> IO.pure(1)

      val program = async[IO](io.await + io.await)

      program.flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }

    "propagate errors outward" in {

      case object Boom extends Throwable
      val io = IO.raiseError[Int](Boom)

      val program = async[IO](io.await)

      program.attempt.flatMap { res =>
        IO {
          res must beEqualTo(Left(Boom))
        }
      }
    }

    "propagate uncaught errors outward" in {

      case object Boom extends Throwable

      def boom(): Unit = throw Boom
      val program = async[IO](boom())

      program.attempt.flatMap { res =>
        IO {
          res must beEqualTo(Left(Boom))
        }
      }
    }

    "propagate canceled outcomes outward" in {

      val io = IO.canceled

      val program = async[IO](io.await)

      program.start.flatMap(_.join).flatMap { res =>
        IO {
          res must beEqualTo(Outcome.canceled[IO, Throwable, Unit])
        }
      }
    }

    "be cancellable" in {

      val program = for {
        ref <- Ref[IO].of(0)
        _ <- async[IO] {
          IO.never[Unit].await
          ref.update(_ + 1).await
        }.start.flatMap(_.cancel)
        result <- ref.get
      } yield {
        result
      }

      program.flatMap { res =>
        IO {
          res must beEqualTo(0)
        }
      }

    }

    "suspend side effects" in {
      var x = 0
      val program = async[IO](x += 1)

      for {
        _ <- IO(x must beEqualTo(0))
        _ <- program
        _ <- IO(x must beEqualTo(1))
        _ <- program
        _ <- IO(x must beEqualTo(2))
      } yield ok
    }
  }

  "async[Kleisli[IO, R, *]]" should {
    type F[A] = Kleisli[IO, Int, A]

    "work on successes" in {
      val io = Temporal[F].sleep(100.millis) >> Kleisli(x => IO.pure(x + 1))

      val program = async[F](io.await + io.await)

      program.run(0).flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }
  }

  "async[OptionT[IO, *]]" should {

    "work on successes" in {
      val io = Temporal[OptionT[IO, *]].sleep(100.millis) >> OptionT.pure[IO](1)

      val program = async[OptionT[IO, *]](io.await + io.await)

      program.value.flatMap { res =>
        IO {
          res must beEqualTo(Some(2))
        }
      }
    }

    "work on None" in {
      val io1 = OptionT.pure[IO](1)
      val io2 = OptionT.none[IO, Int]

      val program = async[OptionT[IO, *]](io1.await + io2.await)

      program.value.flatMap { res =>
        IO {
          res must beEqualTo(None)
        }
      }
    }
  }

  "async[OptionT[OptionT[IO, *], *]" should {
    type F[A] = OptionT[OptionT[IO, *], A]

    "surface None at the right layer (1)" in {
      val io = OptionT.liftF(OptionT.none[IO, Int])

      val program = async[F](io.await)

      program.value.value.flatMap { res =>
        IO {
          res must beEqualTo(None)
        }
      }
    }

    "surface None at the right layer (2)" in {
      val io1 = 1.pure[F]
      val io2 = OptionT.none[OptionT[IO, *], Int]

      val program = async[F](io1.await + io2.await)

      program.value.value.flatMap { res =>
        IO {
          res must beEqualTo(Some(None))
        }
      }
    }
  }

  "async[WriterT[IO, T, *]]" should {
    type F[A] = WriterT[IO, Int, A]

    "surface logged " in {
      val io1 = WriterT(IO((1, 3)))

      val program = async[F](io1.await * io1.await)

      program.run.flatMap { res =>
        IO {
          res must beEqualTo((2, 9))
        }
      }
    }
  }

  type OptionTIO[A] = OptionT[IO, A]
  "async[F]" should {
    "prevent compilation of await[G, *] calls" in {
      val tc = typecheck("async[OptionTIO](IO(1).await)").result
      tc must beLike {
        case TypecheckError(message) =>
          message must contain("expected await to be called on")
          message must contain("cats.data.OptionT")
          message must contain("but was called on cats.effect.IO[Int]")
      }
    }

    "respect nested async[G] calls" in {
      val optionT = OptionT.liftF(IO(1))

      val program =  async[IO]{
        async[OptionTIO](optionT.await).value.await
      }

      program.flatMap { res =>
        IO {
          res must beEqualTo(Some(1))
        }
      }
    }

    "allow for polymorphic usage" in {
      def foo[F[_] : Async] = async[F]{ 1.pure[F].await }

      foo[IO].flatMap { res =>
        IO {
          res must beEqualTo(1)
        }
      }
    }
  }

  "parallel[IO]" should {
    "allow for parallel composition" in {

      val program = Deferred[IO, Int].flatMap { promise =>
        parallel[IO] {
          val res = promise.get.await
          val _ = promise.complete(1).await
          res
        }
      }

      program.flatMap { res =>
        IO {
          res must beEqualTo(1)
        }
      }
    }

    "suspend side effects" in {

      var x = 0
      // the expansion of "parallel" differs based on how many awaits are called (0, 1, 2+)
      val program1 = parallel[IO] { x += 1 }
      val program2 = parallel[IO] { x += 1; IO.pure(1).await }
      val program3 = parallel[IO] { x += 1; IO.pure(1).await + IO.pure(1).await }

      for {
        _ <- IO(x must beEqualTo(0))
        _ <- program1
        _ <- IO(x must beEqualTo(1))
        _ <- program2
        _ <- IO(x must beEqualTo(2))
        _ <- program3
        _ <- IO(x must beEqualTo(3))
      } yield ok
    }

    "prevent compilation of await[G, *] calls" in {
      val tc = typecheck("parallel[OptionTIO](IO(1).await)").result
      tc must beLike {
        case TypecheckError(message) =>
          message must contain("expected await to be called on")
          message must contain("cats.data.OptionT")
          message must contain("but was called on cats.effect.IO[Int]")
      }
    }

    "respect nested async[G] calls" in {
      val optionT = OptionT.liftF(IO(1))

      val program =  parallel[IO]{
        async[OptionTIO](optionT.await).value.await
      }

      program.flatMap { res =>
        IO {
          res must beEqualTo(Some(1))
        }
      }
    }

    "protects against ill-use of definitions" in {
      val tc = typecheck("parallel[IO]{val a = 1; IO(a).await}").result
      val expectedMsg = "Cannot await an effect that uses `value a` defined within the parallel {...} block"
      tc must beLike {
        case TypecheckError(message) =>
          message must beEqualTo(expectedMsg)
      }
    }
  }

}
