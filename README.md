<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt

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

The type [`Rxn[-A, +B]`](core/shared/src/main/scala/dev/tauri/choam/Rxn.scala)
is similar to an effectful function from `A` to `B` (that is, `A ⇒ F[B]`), but:

- The only effect it can perform is lock-free updates to
  [`Ref`s](core/shared/src/main/scala/dev/tauri/choam/Ref.scala)
  (mutable memory locations with a pure API).
  - For example, if `x` is a `Ref[Int]`, then `x.update(_ + 1)` is a `Rxn` which
    (when executed) will increment its value.
- Multiple `Rxn`s can be composed (by using various combinators),
  and the resulting `Rxn` will *update all affected memory locations atomically*.
  - For example, if `y` is also a `Ref[Int]`, then `x.update(_ + 1) >>> y.update(_ + 1)`
    will increment both of them atomically.

## Modules

- [`choam-core`](core/shared/src/main/scala/dev/tauri/choam/):
  - core types, like
    [`Rxn`](core/shared/src/main/scala/dev/tauri/choam/Rxn.scala) and
    [`Ref`](core/shared/src/main/scala/dev/tauri/choam/Ref.scala)
  - integration with synchronous effect types in
    [Cats Effect](https://github.com/typelevel/cats-effect)
- [`choam-data`](data/shared/src/main/scala/dev/tauri/choam/data/):
  data structures, like queues and stacks
- [`choam-async`](async/shared/src/main/scala/dev/tauri/choam/async/):
  - async data structures
  - integration with asynchronous effect types in
    [Cats Effect](https://github.com/typelevel/cats-effect)
- [`choam-stream`](stream/shared/src/main/scala/dev/tauri/choam/stream/):
  integration with [FS2](https://github.com/typelevel/fs2) `Stream`s
- [`choam-laws`](laws/shared/src/main/scala/dev/tauri/choam/laws/):
  properties fulfilled by the various `Rxn` combinators
- [`choam-mcas`](mcas/shared/src/main/scala/dev/tauri/choam/mcas/):
  low-level multi-word compare-and-swap (MCAS/*k*-CAS) implementations

## Related work

- Our `Rxn` is an extended version of *reagents*, described in
  [Reagents: Expressing and Composing Fine-grained Concurrency
  ](http://www.ccis.northeastern.edu/home/turon/reagents.pdf). (Other implementations or reagents:
  [Scala](https://github.com/aturon/ChemistrySet),
  [OCaml](https://github.com/ocamllabs/reagents),
  [Racket](https://github.com/aturon/Caper).)
  The main diferences from the paper are:
  - Only lock-free features (and a few low-level ones) are implemented.
  - `Rxn` has a referentially transparent ("pure functional") API.
  - The interpreter is stack-safe.
  - We also support composing `Rxn`s which modify the same `Ref`
    (thus, an `Rxn` is closer to an STM transaction than a *reagent*;
    see below).
- Multi-word compare-and-swap (MCAS/*k*-CAS) implementations:
  - [A Practical Multi-Word Compare-and-Swap Operation](
    https://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf) (an earlier version used this
    algorithm)
  - [Efficient Multi-word Compare and Swap](
    https://arxiv.org/pdf/2008.02527.pdf) (`MCAS.EMCAS` implements a variant of this algorithm)
  - A simple, non-lock-free algorithm from the Reagents paper (see above) is implemented as
    `MCAS.SpinLockMCAS`
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
      [Cats Effect](https://github.com/typelevel/cats-effect) typeclasses.
      This feature can be used to provide such "waiting" functionality
      (e.g., `AsyncQueue.ringBuffer` is a queue with `enqueue` in `Rxn` and `deque` in `IO`).
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
      [*opacity*](https://nbronson.github.io/scala-stm/semantics.html#opacity); not all
      STM implementations provide this, but some do (e.g., `scala-stm`).
  - Some STM implementations:
    - Haskell: `Control.Concurrent.STM`.
    - Scala: `scala-stm`, `cats-stm`, `ZSTM`.
    - [TL2](https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.90.811&rep=rep1&type=pdf)
      and [SwissTM](https://infoscience.epfl.ch/record/136702/files/pldi127-dragojevic.pdf):
      the system which guarantees *opacity* (see above) for `Rxn`s is based on
      the one in SwissTM (which is itself based on the one in TL2).
