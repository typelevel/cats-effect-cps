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

import cats.syntax.all._
import org.specs2.mutable.Specification
import cps._

class MonadAsyncAwaitSpec extends Specification {

  "async[Option]" should {

    "be Some" in {

      val option = async[Option] {
        val a = "a".some.await
        val b = Option.when(1 + 1 == 2)("b").await
        a + b
      }

      option must beSome("ab")
    }

    "be None" in {

      val option = async[Option] {
        val a = "a".some.await
        val b = Option.when(1 + 1 == 3)("b").await
        a + b
      }

      option must beNone
    }

  }

}
