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

# TODO

## Bugs

- `Exchanger` doesn't work with the new `Rxn`
- `Ctrie` is incomplete
- Can't run benchmarks with Scala 3
- JCStress:
  - `CtrieComposedTest` error: "java.lang.OutOfMemoryError: Java heap space"
  - `EMCASTest` fails with "true, CL, 42, SUCCESSFUL, null"
  - still need to run the tests under `async`

## Other improvements

- Testing:
  - Figure out some tricky race conditions, and test them with JCStress.
    - `Exchanger`
  - LawsSpec:
    - improve generated `Rxn`s, check if they make sense
    - check if `testingEqRxn` makes sense, maybe do structural checking
  - Test with other IO impls (when they cupport ce3)
- EMCAS with simplified IBR:
  - Cleanup after a k-CAS op is finalized:
    - It is enabled now, since it is necessary, to avoid leaking memory.
    - TODO:
      - Measure performance.
      - Measure memory requirements, make sure finalized list is not too big.
- Compile-time detection of:
  - impossible k-CAS operations (2 changes to the same `Ref`)
- Optimization ideas:
  - `Rxn#provide`
  - `Rxn#as`
  - Boxing
  - Rxn interpreter (external interpreter)
  - Review writes/reads in EMCAS, check if we can relax them
  - Ref padding:
    - allocating a padded Ref is much slower than an unpadded
    - however, false sharing could be a problem
    - which should be the default? padded/unpadded?
  - Ref initialization:
    - currently: volatile write
    - a release write would be faster
      - it would also mean that there is no perf. difference bw. `empty[A]` and `apply(nullOf[A])`
    - but: doing only a release write might not be safe
      - if another thread gets the `Ref` through an acquire read, it should be OK
      - otherwise, it might not see the contents
        - e.g., when calling `Ref.unsafe`, and storing it in a plain `var`
        - could it happen without using unsafe? (or `unsafeRun*` on the IO)
- Cleanup:
  - Review benchmarks, remove useless ones
- Async:
  - maybe move async things to `choam-async`(?)
  - integration with FS2 (`choam-stream`):
    - implement FS2 data structures (`Queue`, ...) with reagents
    - optimize `AsyncQueue` stream
- API cleanup:
  - check if by-name param makes sense for `>>`
    - is it stack-safe?
    - if yes, can we make it faster than the default implementation?
  - separate unsafe/low-level API for `invisibleRead` and other dangerous
    - (unsafe) thread-confined mode for running a `Rxn` (with `NaiveKCAS` or something even more simple)
  - Rxn.onRetry?
  - Rxn.delay?
    - allocating (but: only `Ref` really needs it, others are built on that)
    - calling async callbacks (but: only `Promise` needs it, others don't)
    - allocating `Exchanger` arrays (this is similar to `Ref`)
  - move `KCAS` into separate JAR, figure out proper API (`choam-mcas`)
  - move data structures into separate JAR (`choam-data`)
  - maybe: move async stuff into separate JAR (`choam-async`)
    - but: what to do with `Reactive.Async`? (could remain in core)
  - compare with `Ref` in cats-effect: similar things should have similar names
  - Maybe rename `Ref`?
    - Collision with `cats.effect.kernel.Ref`
    - Although it is hard to confuse them
    - Name ideas:
      - `RVar` (like `TVar` for STM refs)
      - `RRef`
      - ???
  - Handling errors?
    - Generally, we shouldn't raise errors in a reaction
      - If something can fail, return `Option` or `Either`
    - Need to review and document the handling of exceptions
      - they should fall-through, but with cleanup
    - Transient errors can sometimes be handled with `+` (`Choice`)
      - but sometimes this can cause infinite retry
- Cancellation support
  - `Thread.interrupt`
  - cats-effect cancellation?
- Composition of maybe-infinitely-retrying reactions:
  - `stack.pop`, if empty, retries forever (unsafe, because non-lock-free)
  - `exchanger.exchange`, if no partner found, retries forever (also unsafe)
  - each can be made safe by `.?` (will only try once)
  - however, composing the two is also an option (elimination stack):
    - `(stack.pop + exchanger.exchange).?` is safe, but built from unsafe parts
    - `(pop.? + exchange.?)` is safe, built from safe parts
  - Can we have an API for composing unsafe parts into something which is safe?
    - e.g., `PartialRxn[A, B]`
    - `.?` would make a (safe) `Rxn` from it
- Think about global / thread-local state:
  - if we're running in IO, we might use something else
  - however, IBR probably really needs thread-locals
  - think about possible problems with fibers

## Misc.

- `LongRef`, `IntRef`, ... (benchmarks needed, it might not make sense)
- `Ref` which is backed by mmapped memory(?)
- Other data structures:
  - ctrie-set
  - `SkipListMap`, `SkipListSet`
  - https://dl.acm.org/doi/10.1145/1989493.1989550 ?
  - dual data structures:
    - e.g., stack
    - push: like normal stack
    - pop: if empty, spin wait for a small time, then return an async `F[A]`
    - what API could represent this?
      - maybe `pop: Axn[Either[A, F[A]]]`?
      - or maybe simply `pop: F[A]`, which is synchronous in the first case?
      - (i.e., this could be an impl. detail of `AsyncStack`)
      - or maybe:
        - new `AsyncRxn[A, B]` type, which could be async
        - `pop: AsyncRxn[Unit, A]`
        - `def unsafeRun(ar: AsyncRxn[A, B], a: A): Either[F[A], A]`
        - `def unsafeToF(ar: AsyncRxn[A, B], a: A): F[A] = unsafeRun(...).fold(x => x, F.pure)`
- "Laws" for the `Rxn` combinators, e.g.:
  - choice prefers the first option
  - `flatMap` <-> `>>>` and `computed`
  - `updWith` then `ret` <-> `modify`
- scalajs
