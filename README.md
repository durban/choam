<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt

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

*Experiments with composable lock-free concurrency.*

The type [`React[-A, +B]`](core/src/main/scala/dev/tauri/choam/React.scala)
is similar to an effectful function from `A` to `B`, but:

- The only effect it can perform is lock-free updates to
  [`Ref`s](core/src/main/scala/dev/tauri/choam/kcas/ref.scala)
  (mutable memory locations with a pure API).
- Multiple `React`s can be composed, by using various combinators,
  and the resulting `React` will *update all affected memory locations atomically*.

## Related work

- Our `React` is a simplified version of *reagents*, described in [Reagents:
  Expressing and Composing Fine-grained Concurrency](https://people.mpi-sws.org/~turon/reagents.pdf). Other implementations:
  [Scala](https://github.com/aturon/ChemistrySet),
  [OCaml](https://github.com/ocamllabs/reagents),
  [Racket](https://github.com/aturon/Caper).
- *k*-CAS (multi-word compare-and-swap) implementations:
  - [A Practical Multi-Word Compare-and-Swap Operation](
    https://www.cl.cam.ac.uk/research/srg/netos/papers/2002-casn.pdf)
  - [Efficient Multi-word Compare and Swap](
    https://arxiv.org/pdf/2008.02527.pdf)
