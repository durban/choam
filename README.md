<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt

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

The type [`Rxn[-A, +B]`](core/shared/src/main/scala/dev/tauri/choam/Rxn.scala)
is similar to an effectful function from `A` to `B` (that is, `A ⇒ F[B]`), but:

- The only effect it can perform is lock-free updates to
  [`Ref`s](core/shared/src/main/scala/dev/tauri/choam/Ref.scala)
  (mutable memory locations with a pure API).
  - For example, if `x` is a `Ref[Int]`, then `x.update(_ + 1)` is a `Rxn` which
    (when executed) will increment its value.
- Multiple `Rxn`s can be composed, by using various combinators,
  and the resulting `Rxn` will *update all affected memory locations atomically*.
  - For example, if `y` is also a `Ref[Int]`, then `x.update(_ + 1) >>> y.update(_ + 1)`
    will increment both of them atomically.
- However, conflicting `Rxn`s cannot be composed. That is, `Rxn`s which
  update the same `Ref` are not allowed to be composed.
  - Currently composing conflicting `Rxn`s causes a runtime error.
  - Future work: either detecting this error during compile time,
    or somehow allowing conflicting updates.

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
  low-level multi-word compare-and-swap (MCAS/*k*-CAS) implementation

## Related work

- Our `Rxn` is a lock-free version of *reagents*, described in [Reagents:
  Expressing and Composing Fine-grained Concurrency](https://people.mpi-sws.org/~turon/reagents.pdf). Other implementations:
  [Scala](https://github.com/aturon/ChemistrySet),
  [OCaml](https://github.com/ocamllabs/reagents),
  [Racket](https://github.com/aturon/Caper).
- Multi-word compare-and-swap (*k*-CAS) implementations:
  - [A Practical Multi-Word Compare-and-Swap Operation](
    https://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf)
  - [Efficient Multi-word Compare and Swap](
    https://arxiv.org/pdf/2008.02527.pdf)
