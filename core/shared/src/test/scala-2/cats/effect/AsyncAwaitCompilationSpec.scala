package cats.effect

import org.specs2.mutable.Specification

class AsyncAwaitCompilationSpec extends Specification {

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
  }
}
