<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt

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

*Experiments with composable lock-free concurrency*

## Overview

The type [`Rxn[-A, +B]`](core/shared/src/main/scala/dev/tauri/choam/core/Rxn.scala)
is similar to an effectful function from `A` to `B` (that is, `A => F[B]`), but:

- The only effect it can perform is lock-free updates to
  [`Ref`s](core/shared/src/main/scala/dev/tauri/choam/refs/Ref.scala)
  (mutable memory locations with a pure API).
  - For example, if `x` is a `Ref[Int]`, then `x.update(_ + 1)` is a `Rxn` which
    (when executed) will increment its value.
- Multiple `Rxn`s can be composed (by using various combinators),
  and the resulting `Rxn` will *update all affected memory locations atomically*.
  - For example, if `y` is also a `Ref[Int]`, then `x.update(_ + 1) *> y.update(_ + 1)`
    will increment both of them atomically.

## Modules

- [`choam-core`](core/shared/src/main/scala/dev/tauri/choam/):
  - core types, like
    [`Rxn`](core/shared/src/main/scala/dev/tauri/choam/core/Rxn.scala) and
    [`Ref`](core/shared/src/main/scala/dev/tauri/choam/refs/Ref.scala)
  - integration with synchronous effect types in
    [Cats Effect](https://github.com/typelevel/cats-effect)
- [`choam-data`](data/shared/src/main/scala/dev/tauri/choam/data/):
  concurrent data structures:
  - queues
  - stacks
  - hash- and ordered maps and sets
  - counter
- [`choam-async`](async/shared/src/main/scala/dev/tauri/choam/async/):
  - integration with asynchronous effect types in
    [Cats Effect](https://github.com/typelevel/cats-effect):
    - the main integration point is a `Promise`, which can be
      completed as a `Rxn`, and can be waited on as an async `F[_]`:
      ```scala
      trait Promise[F[_], A] {
        def complete: Rxn[A, Boolean]
        def get: F[A]
      }
      ```
    - async (dual) data structures can be built on this primitive
  - async data structures; some of their operations are
    *semantically* blocking (i.e., [fiber blocking
    ](https://typelevel.org/cats-effect/docs/thread-model#fiber-blocking-previously-semantic-blocking)),
    and so are in an async `F[_]`):
    - queues
    - stack
- [`choam-stream`](stream/shared/src/main/scala/dev/tauri/choam/stream/):
  integration with [FS2](https://github.com/typelevel/fs2) `Stream`s
- [`choam-laws`](laws/shared/src/main/scala/dev/tauri/choam/laws/):
  properties fulfilled by the various `Rxn` combinators
- Internal modules (don't use them directly):
  - [`choam-mcas`](mcas/shared/src/main/scala/dev/tauri/choam/mcas/):
    low-level multi-word compare-and-swap (MCAS/*k*-CAS) implementations
  - [`choam-skiplist`](skiplist/jvm/src/main/scala/dev/tauri/choam/skiplist/):
    a concurrent skip list map for internal use

## Related work

- Our `Rxn` is an extended version of *reagents*, described in
  [Reagents: Expressing and Composing Fine-grained Concurrency
  ](https://web.archive.org/web/20220214132428/https://www.ccis.northeastern.edu/home/turon/reagents.pdf). (Other implementations or reagents:
  [Scala](https://github.com/aturon/ChemistrySet),
  [OCaml](https://github.com/ocamllabs/reagents),
  [Racket](https://github.com/aturon/Caper).)
  The main diferences from the paper are:
  - Only lock-free features (and a few low-level ones) are implemented.
  - `Rxn` has a referentially transparent ("pure functional" / "programs as values") API.
  - The interpreter (that executes `Rxn`s) is stack-safe.
  - We also support composing `Rxn`s which modify the same `Ref`
    (thus, an `Rxn` is closer to an STM transaction than a *reagent*;
    see below).
  - Reads are always guaranteed to be consistent (this is called *opacity*, see below).
- Multi-word compare-and-swap (MCAS/*k*-CAS) implementations:
  - [A Practical Multi-Word Compare-and-Swap Operation](https://web.archive.org/web/20220121034605/https://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf)
    (an earlier version used this algorithm)
  - [Efficient Multi-word Compare and Swap](https://web.archive.org/web/20220215225848/https://arxiv.org/pdf/2008.02527.pdf)
    (`Mcas.Emcas` implements a variant of this algorithm; this is the default algorithm we use on the JVM)
  - A simple, non-lock-free algorithm from the Reagents paper (see above) is implemented as
    `Mcas.SpinLockMcas` (we use it for testing)
- Software transactional memory (STM)
  - A `Rxn` is somewhat similar to a memory transaction, but there are
    important differences:
    - A `Rxn` is lock-free by construction (unless it's infinitely recursive, or an
      `unsafe` method was used to create it); STM transactions are not necessarily
      lock-free (e.g., STM "retry").
    - As a consequence of the previous point, `Rxn` cannot be used to implement
      "inherently not lock-free" logic (e.g., asynchronously waiting on a
      condition set by another thread/fiber/similar). However, `Rxn` is
      interoperable with async data types which implement
      [Cats Effect](https://github.com/typelevel/cats-effect) typeclasses
      (see the ``choam-async`` module). This feature can be used to provide such
      "waiting" functionality (e.g., `dev.tauri.choam.async.AsyncQueue.ringBuffer`
      is a queue with `enqueue` in `Rxn` and `deque` in, e.g., `IO` or another async `F[_]`).
    - The implementation (the `Rxn` interpreter) is also lock-free; STM implementations
      are usually not (although there are exceptions).
    - STM transactions usually have a way of raising/handling errors
      (e.g., `MonadError`); `Rxn` has no such feature (of course return
      values can encode errors with `Option`, `Either`, or similar).
    - Some STM systems allow access to transactional memory from
      non-transactional code; `Rxn` doesn't support this, the contents of an
      `r: Ref[A]` can only be accessed from inside a `Rxn` (although there is a
      read-only escape hatch: `r.unsafeDirectRead`).
  - Similarities between `Rxn`s and STM transactions include the following:
    - atomicity
    - consistency
    - isolation
    - `Rxn` also provides a correctness property called
      [*opacity*](https://web.archive.org/web/20200918092715/https://nbronson.github.io/scala-stm/semantics.html#opacity);
      a lot of STM implementations also guarantee this property (e.g., `scala-stm`),
      but not all of them. Opacity basically guarantees that all observed values
      are consistent with each other, even in running `Rxn`s (some STM systems only
      guarantee such consistency for transactions which actually commit).
  - Some STM implementations:
    - Haskell: `Control.Concurrent.STM`.
    - Scala: `scala-stm`, `cats-stm`, `ZSTM`.
    - [TL2](https://web.archive.org/web/20220205171142/https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.90.811&rep=rep1&type=pdf)
      and [SwissTM](https://web.archive.org/web/20220215230304/https://www.researchgate.net/profile/Aleksandar-Dragojevic/publication/37470225_Stretching_Transactional_Memory/links/0912f50d430e2cf991000000/Stretching-Transactional-Memory.pdf):
      the system which guarantees *opacity* (see above) for `Rxn`s is based on
      the one in SwissTM (which is itself based on the one in TL2). However, TL2 and SwissTM
      are lock-based STM implementations; our implementation is lock-free.
