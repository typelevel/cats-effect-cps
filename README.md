# cats-effect-cps
<!-- [![Latest version](https://index.scala-lang.org/typelevel/cats-effect/cats-effect/latest.svg?color=orange)](https://index.scala-lang.org/typelevel/cats-effect/cats-effect) -->
[![Discord](https://img.shields.io/discord/632277896739946517.svg?label=&logo=discord&logoColor=ffffff&color=404244&labelColor=6A7EC2)](https://discord.gg/QNnHKHq5Ts)

This is an incubator library for `async`/`await` syntax in Cats Effect, currently targeting Scala 2 exclusively (due to missing compiler support in Scala 3). The `async`/`await` functionality within the Scala compiler is *itself* quite experimental, and thus this library should also be considered experimental until upstream support stabilizes. Once that happens and this implementation is considered to be finalized, the functionality in this library will be folded into Cats Effect itself and this library will be archived.

"CPS" stands for "[Continuation Passing Style](https://en.wikipedia.org/wiki/Continuation-passing_style)". Related project targeting Scala 3: [rssh/cps-async-connect](https://github.com/rssh/cps-async-connect). This functionality is quite similar to similar functionality in [JavaScript](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Statements/async_function), [Rust](https://rust-lang.github.io/async-book/01_getting_started/04_async_await_primer.html), [Kotlin](https://kotlinlang.org/docs/composing-suspending-functions.html), and many other languages. The primary difference being that, in this library, the `async` marker is a *lexical block*, whereas in other languages the marker is usually a modifier applied at the function level.

Special thanks to [Jason Zaugg](https://github.com/retronym) for his work on the implementation of `-Xasync` within scalac.

## Usage

```sbt
libraryDependencies += "org.typelevel" %% "cats-effect-cps" % "<version>"
scalacOptions += "-Xasync"  // required to enable compiler support
```

Published for Scala 2.13 and 2.12, cross-build with ScalaJS 1.6. Depends on Cats Effect 3.1.0 or higher.

## Example

Consider the following program written using a `for`-comprehension (pretend `talkToServer` and `writeToFile` exist and do the obvious things, likely asynchronously):

```scala
import cats.effect._

for {
  results1 <- talkToServer("request1", None)
  _ <- IO.sleep(100.millis)
  results2 <- talkToServer("request2", Some(results1.data))

  back <- if (results2.isOK) {
    for {
      _ <- writeToFile(results2.data)
      _ <- IO.println("done!")
    } yield true
  } else {
    IO.println("abort abort abort").as(false)
  }
} yield back
```

Using cats-effect-cps, we can choose to rewrite the above in the following direct style:

```scala
import cats.effect.cps._

val dsl = new AsyncAwaitDsl[IO]
import dsl._

async {
  val results1 = await(talkToServer("request1", None))
  await(IO.sleep(100.millis))

  val results2 = await(talkToServer("request2", Some(results1.data)))

  if (results2.isOK) {
    await(writeToFile(results2.data))
    await(IO.println("done!"))
    true
  } else {
    await(IO.println("abort abort abort"))
    false
  }
}
```

There are no meaningful performance differences between these two encodings. They do almost exactly the same thing using different syntax, similar to how `for`-comprehensions are actually `flatMap` and `map` functions under the surface.
