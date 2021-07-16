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

import org.specs2.execute.{Typecheck, TypecheckError}
import org.specs2.mutable.Specification

class AsyncAwaitCompilationSpec extends Specification {
  import cps._

  "async[F]" should {
    "prevent compilation of await[G, *] calls" in {
      val tc = Typecheck("async[({ type L[a] = cats.data.OptionT[IO, a] })#L](IO(1).await)").result
      tc must beLike {
        case TypecheckError(message) =>
          message must contain("expected await to be called on")
          message must contain("cats.data.OptionT")
          message must contain("but was called on cats.effect.IO[Int]")
      }
    }
  }
}
