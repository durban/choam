<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

--->

# CHOAM

[![choam-core version](https://index.scala-lang.org/durban/choam/choam-core/latest.svg)](https://index.scala-lang.org/durban/choam/choam-core)

*Experiments with composable lock-free concurrency*

## Overview

The type [`Rxn[+A]`](core/shared/src/main/scala/dev/tauri/choam/core/Rxn.scala)
is an effect type with result type `A`. Thus, it is similar to, e.g., `IO[A]`, but:

- The only effect it can perform is lock-free updates to
  [`Ref`s](core/shared/src/main/scala/dev/tauri/choam/refs/Ref.scala)
  (mutable memory locations with a pure API).
  - For example, if `x` is a `Ref[Int]`, then `x.update(_ + 1)` is a `Rxn[Unit]` which
    (when executed) will increment its value.
- Multiple `Rxn`s can be composed (by using various combinators),
  and the resulting `Rxn` will *update all affected memory locations atomically*.
  - For example, if `y` is also a `Ref[Int]`, then `x.update(_ + 1) *> y.update(_ + 1)`
    will increment both of them *atomically*.

## Getting started

```scala
libraryDependencies += "dev.tauri" %%% "choam-core" % choamVersion // see above for latest version
```

The `choam-core` module contains the fundamental types for working with `Rxn`s.
For more modules, see [below](#modules).

The complete version of the example [above](#overview), (which increments the value of
two `Ref`s) is as follows:

<!-- Note: this needs to be kept in sync with `ReadmeSpec`! -->
```scala
import dev.tauri.choam.core.{ Rxn, Ref }

def incrBoth(x: Ref[Int], y: Ref[Int]): Rxn[Unit] = {
  x.update(_ + 1) *> y.update(_ + 1)
}
```

As an example, we can execute it with `cats.effect.IO` like this
(the `choam-ce` module is also needed):

<!-- Note: this needs to be kept in sync with `ReadmeSpec`! -->
```scala
import cats.effect.{ IO, IOApp }
import dev.tauri.choam.ce.RxnAppMixin

object MyMain extends IOApp.Simple with RxnAppMixin {
  override def run: IO[Unit] = for {
    // create two refs:
    x <- Ref(0).run[IO]
    y <- Ref(42).run[IO]
    // increment their values atomically:
    _ <- incrBoth(x, y).run[IO]
    // check that it happened:
    xv <- x.get.run[IO]
    yv <- y.get.run[IO]
    _ <- IO {
      assert(xv == 1)
      assert(yv == 43)
    }
  } yield ()
}
```

## Software Transactional Memory (STM) [_work in progress_]

As noted [below](#related-work), `Rxn` is not a full-featured STM implementation (notably,
it lacks Haskell-style "modular blocking"). However, the `dev.tauri.choam.stm`
package contains a full-blown STM, built on top of `Rxn`. For now, this package is
experimental (i.e., no backwards compatibility), and also lacks some basic features, but it
_does_ have modular blocking (`Txn.retry`).

## Modules

- [`choam-core`](core/shared/src/main/scala/dev/tauri/choam/):
  - core types, like
    [`Rxn`](core/shared/src/main/scala/dev/tauri/choam/core/Rxn.scala) and
    [`Ref`](core/shared/src/main/scala/dev/tauri/choam/core/Ref.scala)
  - integration with effect types implementing the
    [Cats Effect](https://github.com/typelevel/cats-effect) typeclasses
    (specifically `Sync`/`Async`)
  - _experimental_ software transactional memory (STM) built on `Rxn`
- [`choam-data`](data/shared/src/main/scala/dev/tauri/choam/data/):
  concurrent data structures built on `Rxn`
  - Examples: queues, stacks, hash- and ordered maps and sets
- [`choam-async`](async/shared/src/main/scala/dev/tauri/choam/async/):
  - Asynchronous data structures; some of their operations are
    *semantically* blocking (i.e., [fiber blocking
    ](https://typelevel.org/cats-effect/docs/thread-model#fiber-blocking-previously-semantic-blocking)),
    and so are in an `F[_] : Async` (note, that these `F[A]` operations are – obviously – *not* lock-free)
  - These data structures (typically) also have some (lock-free) `Rxn` operations; thus they
    provide a "bridge" between the (synchronous, lock-free) `Rxn` "world", and the (asynchronous) `F[_]` "world".
  - <a name="promise"></a>The simplest example is the data type `dev.tauri.choam.async.Promise`, which can be
    completed as a `Rxn`, and can be waited on as an async `F[_]`:
      ```scala
      trait Promise[A] { // simplified API
        def complete(a: A): Rxn[Boolean]
        def get[F[_]]: F[A]
      }
      ```

- [`choam-stream`](stream/shared/src/main/scala/dev/tauri/choam/stream/):
  integration with `fs2.Stream`s
- [`choam-ce`](ce/shared/src/main/scala/dev/tauri/choam/ce/):
  integration with `cats.effect.IOApp` (for convenience)
- [`choam-zi`](zi/shared/src/main/scala/dev/tauri/choam/zi/):
  integration with `zio.ZIOApp` (for convenience)
- [`choam-profiler`](profiler/src/main/scala/dev/tauri/choam/profiler/):
  JMH profiler "plugin" for `Rxn` statistics/measurements; enable it with
  `-prof dev.tauri.choam.profiler.RxnProfiler`.
- Internal modules (don't use them directly):
  - [`choam-mcas`](mcas/shared/src/main/scala/dev/tauri/choam/internal/mcas/):
    low-level multi-word compare-and-swap (MCAS/*k*-CAS) implementations
  - [`choam-internal`](internal/shared/src/main/scala/dev/tauri/choam/internal/):
    a concurrent skip list map and other utilities for internal use
  - [`choam-laws`](laws/shared/src/main/scala/dev/tauri/choam/laws/):
    properties fulfilled by the various `Rxn` combinators

JARs are on Maven Central. Browsable Scaladoc is available [here](
https://www.javadoc.io/doc/dev.tauri/choam-docs_2.13/latest/index.html).

## Related work

- Our `Rxn` is an extended version of *reagents*, described in [the Reagents paper][1][^1]
  (originally implemented [in Scala](https://github.com/aturon/ChemistrySet); see also other
  implementations [in OCaml](https://github.com/ocaml-multicore/reagents) and [in Racket](https://github.com/aturon/Caper).)
  The main diferences from the paper are:
  - Only lock-free features (and a few low-level ones) are implemented.
  - `Rxn` has a referentially transparent ("purely functional" / "programs as values") monadic API.
    (A limited imperative API is also available in the `dev.tauri.choam.unsafe` package.)
  - The interpreter (that executes `Rxn`s) is stack-safe.
  - We also support composing `Rxn`s which modify the same `Ref`
    (thus, an `Rxn` is closer to an STM transaction than a *reagent*;
    see below).
  - Reads are _always_ guaranteed to be consistent (this is called *opacity*, see below).

[1]: https://www.ccs.neu.edu/home/turon/reagents.pdf
[^1]: Turon, Aaron. "Reagents: expressing and composing fine-grained concurrency." In Proceedings of the 33rd ACM SIGPLAN Conference on Programming Language Design and Implementation, pp. 157-168. 2012.

- Multi-word compare-and-swap (MCAS/*k*-CAS) implementations:
  - In an earlier version we used [CASN by Harris et al.][2][^2]
  - The current version uses [EMCAS by Guerraoui et al.][3][^3];
    `Mcas.Emcas` implements a variant of this algorithm, this is the default algorithm we use on the JVM;
    on JS we use a trivial single-threaded algorithm.
  - A simple, non-lock-free algorithm from [the Reagents paper][1][^1] is implemented as
    `Mcas.SpinLockMcas` (we use it for testing).

[2]: https://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf
[^2]: Harris, Timothy L., Keir Fraser, and Ian A. Pratt. "A practical multi-word compare-and-swap operation." In Distributed Computing: 16th International Conference, DISC 2002 Toulouse, France, October 28–30, 2002 Proceedings 16, pp. 265-279. Springer Berlin Heidelberg, 2002.

[3]: https://arxiv.org/pdf/2008.02527.pdf
[^3]: Guerraoui, Rachid, Alex Kogan, Virendra J. Marathe, and Igor Zablotchi. "Efficient Multi-Word Compare and Swap." In 34th International Symposium on Distributed Computing. 2020.

- Software transactional memory (STM)
  - A `Rxn` is somewhat similar to a memory transaction, but there are
    important differences:
    - A `Rxn` is lock-free by construction (but see [below](#lock-freedom)); STM transactions are not (necessarily)
      lock-free (see, e.g., the "retry" STM operation, called ["modular blocking" in Haskell][4][^4]).
    - As a consequence of the previous point, `Rxn` cannot be used to implement
      "inherently non-lock-free" logic (e.g., asynchronously waiting on a
      condition set by another thread/fiber/similar). However, `Rxn` is
      interoperable with async data types which implement
      [Cats Effect](https://github.com/typelevel/cats-effect) typeclasses
      (see the `choam-async` module). This feature can be used to provide such
      "waiting" functionality (e.g., with the `dev.tauri.choam.async.Promise` type,
      see [above](#promise)).
    - The implementation (the `Rxn` interpreter) is also lock-free; STM implementations
      usually use fine-grained locking (although there are exceptions).
    - STM transactions usually have a way of raising/handling errors
      (e.g., `MonadError`); `Rxn` has no such feature (but of course return
      values can encode errors with `Option`, `Either`, or similar).
    - Some STM systems allow access to transactional memory from
      non-transactional code; `Rxn` doesn't support this, the contents of a
      `Ref[A]` can only be accessed from inside a `Rxn`.
  - Similarities between `Rxn`s and STM transactions include the following:
    - Atomicity, consistency and isolation.
    - `Rxn` also provides a correctness property called
      [*opacity*][5][^5]; see a short introduction [here][opacity_intro].
      A lot of STM implementations also guarantee this property (e.g., ScalaSTM),
      but not all of them. Opacity basically guarantees that all observed values
      are consistent with each other, even in running `Rxn`s (some STM systems only
      guarantee such consistency for transactions which actually commit).
  - Some STM implementations:
    - Haskell: [`Control.Concurrent.STM`](https://hackage.haskell.org/package/stm).
    - Scala:
      [Cats STM](https://github.com/TimWSpence/cats-stm),
      [`kyo-stm`](https://github.com/getkyo/kyo/tree/main/kyo-stm/shared/src/main/scala/kyo),
      [ScalaSTM](https://github.com/scala-stm/scala-stm),
      [ZSTM](https://github.com/zio/zio/tree/series/2.x/core/shared/src/main/scala/zio/stm).
    - Kotlin: [`arrow-fx-stm`](https://arrow-kt.io/learn/coroutines/stm).
    - OCaml: [Kcas](https://github.com/ocaml-multicore/kcas).
    - [TL2][6][^6] and [SwissTM][7][^7]:
      the system which guarantees *opacity* (see above) for `Rxn`s is based on
      the one in SwissTM (which is itself based on the one in TL2). However, TL2 and SwissTM
      are lock-based STM implementations; our implementation is lock-free.
    - We also use some ideas from the [Commit Phase Variations paper][8][^8].

[4]: https://dl.acm.org/doi/pdf/10.1145/1378704.1378725
[^4]: Harris, Tim, Simon Marlow, Simon Peyton-Jones, and Maurice Herlihy. "Composable memory transactions." In Proceedings of the tenth ACM SIGPLAN symposium on Principles and practice of parallel programming, pp. 48-60. 2005.

[5]: https://infoscience.epfl.ch/record/114303/files/opacity-ppopp08.pdf
[^5]: Guerraoui, Rachid, and Michal Kapalka. "On the correctness of transactional memory." In Proceedings of the 13th ACM SIGPLAN Symposium on Principles and practice of parallel programming, pp. 175-184. 2008.

[opacity_intro]: https://nbronson.github.io/scala-stm/semantics.html#opacity

[6]: https://disco.ethz.ch/courses/fs11/seminar/paper/johannes-2-1.pdf
[^6]: Dice, Dave, Ori Shalev, and Nir Shavit. "Transactional locking II." In International Symposium on Distributed Computing, pp. 194-208. Berlin, Heidelberg: Springer Berlin Heidelberg, 2006.

[7]: https://infoscience.epfl.ch/server/api/core/bitstreams/6b454d6b-0ae9-4b37-b341-6e2d092aef8e/content
[^7]: Dragojević, Aleksandar, Rachid Guerraoui, and Michal Kapalka. "Stretching transactional memory." ACM sigplan notices 44, no. 6 (2009): 155-165.

[8]: https://repository.rice.edu/server/api/core/bitstreams/ec929767-5e4b-4c8e-9704-c649bf6328c9/content
[^8]: Zhang, Rui, Zoran Budimlic, and William N. Scherer III. Commit phase variations in timestamp-based software transactional memory. Technical Report TR08-03, Rice University, 2008.

## Compatibility and assumptions

["Early" SemVer 2.0.0](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html#early-semver-and-sbt-version-policy) _binary_ backwards compatibility, with the following exceptions:

- The versions of `choam-` modules must match *exactly* (e.g., *don't* use `"choam-data" % "0.4.1"`
  with `"choam-core" % "0.4.0"`).
  - In sbt ⩾ 1.10.0 this can be ensured like this:
    ```scala
    csrSameVersions += Set(sbt.librarymanagement.InclExclRule("dev.tauri", "choam-*"))
    ```
- There is no backwards compatibility when:
  - using APIs which are non-public in Scala (even though some of these are public in the bytecode);
  - inheriting `sealed` classes/traits (even though this may not be enforced by the bytecode);
  - using `*.internal.*` packages (e.g., `dev.tauri.choam.internal.mcas`);
  - using `unsafe` APIs (e.g., `Rxn.unsafe.retry` or the contents of the `dev.tauri.choam.unsafe` package).
  - using the contents of the `dev.tauri.choam.stm` package (our STM is experimental for now)
- There is no backwards compatibility for these modules:
  - `choam-ce`
  - `choam-zi`
  - `choam-mcas`
  - `choam-internal`
  - `choam-laws`
  - `choam-stream`
  - `choam-profiler`
  - `choam-docs`
  - (and all unpublished modules)
- There is no backwards compatibility for "hash" versions (e.g., `0.4-39d987a` or `0.4.3-2-39d987a`).

### Supported platforms:

- Platforms:
  - JVM:
    - versions ⩾ 11
    - tested on OpenJDK, Graal, and OpenJ9 (but should work on others)
    - for secure random number generation, either the `Windows-PRNG`
      or (`/dev/random` and `/dev/urandom`) need to be available
  - Scala.js:
    - works, but not really interesting (we assume no multithreading)
    - provided to make cross-compiling easier for projects which use CHOAM
    - for secure random number generation, a `java.security.SecureRandom`
      implementation needs to be available (see [here](https://github.com/scala-js/scala-js-java-securerandom))
- Scala versions: cross-compiled for 2.13 and 3.3

### Lock-freedom

`Rxn`s are lock-free by construction, if the following assumptions hold:

- No "infinite loops" are created (e.g., by infinitely recursive `flatMap`s)
- No `unsafe` operations are used (e.g., `Rxn.unsafe.retry` is obviously not lock-free)
- We assume instances of `FunctionN` to be pure and total
- We assume that certain JVM operations are lock-free:
  - `VarHandle` operations (e.g., `compareAndSet`)
    - in practice, this is true on 64-bit platforms
    - on 32-bit platforms some of these *might* use a lock
  - GC and classloading
    - in practice, the GC sometimes probably uses locks
    - and classloaders sometimes also might use locks
  - `ThreadLocalRandom`, `ThreadLocal`
- Certain `Rxn` operations require extra assumptions:
  - `Rxn.secureRandom` and `UUIDGen` use the OS RNG, which might block
    (although we *really* try to use the non-blocking ones)
  - in `choam-async` we assume that calling a CE `Async` callback is lock-free
    (in `cats.effect.IO`, as of version 3.5.7, this is not technically true)
- Executing a `Rxn` with a `Rxn.Strategy` other than `Rxn.Strategy.Spin`
  is not necessarily lock-free
- Only the default `Mcas` is lock-free, other `Mcas` implementations may not be

Also note, that while `Rxn` operations are lock-free (if these assumptions hold),
operations in an async `F[_]` effect might not be lock-free (an obvious example is
`Promise#get`, which is an async `F[A]`, *not* a `Rxn`).
