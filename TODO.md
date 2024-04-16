<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt

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

- Can't run benchmarks with Scala 3
- `GenWaitList` doesn't handle lost wakeups
  - this affects basically all data structures in `choam-async`
  - if a suspended operation (e.g., a stack `pop`) is cancelled, this can cause lost items
  - it's unclear if we can fix this with the current CE API
  - and even if we would know that the wakeup is lost, it's already too late: the `Rxn` have been already committed

## Other improvements

- Testing:
  - JCStress:
    - `Exchanger`
    - replacing descriptors (weakref?)
    - Other things (Promise?)
  - Test with other IO impls (when they support ce3)
- Optimization ideas:
  - Exchanger: there is a lot of `Array[Byte]` copying
  - Reducing allocations (we're allocating _a lot_)
    - EMCAS (maybe reusing descriptors?)
    - Rxn
      - lots of `Rxn` instances
      - `ObjStack.Lst`
  - Review writes/reads in EMCAS, check if we can relax them
  - Ref padding:
    - allocating a padded Ref is much slower than an unpadded
    - however, false sharing could be a problem
  - Ref initialization:
    - currently: volatile write
    - a release write would be faster
      - it would also mean that there is no perf. difference bw. `empty[A]` and `apply(nullOf[A])`
    - but: doing only a release write might not be safe
      - if another thread gets the `Ref` through an acquire read, it should be OK
      - otherwise, it might not see the contents
        - e.g., when calling `Ref.unsafe`, and storing it in a plain `var`
        - could it happen without using unsafe? (or `unsafeRun*` on the IO)
      - but: it probably might already not see the contents with the volatile write (that only works for `final`s)
- Cleanup:
  - Review benchmarks, remove useless ones
- Async:
  - integration with FS2 (`choam-stream`):
    - Channel?
    - Optimize SignallingRef
- API cleanup:
  - MCAS API review
    - is it usable outside of `choam`?
    - if not, it doesn't really make sense to have it in a separate module(?)
      - being in the same module would simplify using `ThreadContext` for `Rxn`-things
  - Rename `flatMapF`
    - maybe `semiFlatMap` (or `semiflatMap`?)
    - or `subflatMap`?
  - `Rxn.delay` use cases:
    - allocating:
      - `Ref` (most others are built on this)
      - `Ref.array`
      - `Exchanger`
    - calling async callbacks:
      - only `Promise` really needs it
      - `[Gen]WaitList` (as an optimization, to avoid `Promise`)
    - other special cases:
      - `UUIDGen`
      - `Unique` (this is a special case of "allocating")
      - `Clock`
      - `cats.effect.std.Random`
      - `Ttrie` (to avoid `Rxn`-level contention)
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
      - they should fall-through, but with cleanup (if needed)
      - (maybe: do not guarantee any specific behavior for now)
    - Transient errors can sometimes be handled with `+` (`Choice`)
      - but sometimes this can cause infinite retry
- Composition of maybe-infinitely-retrying reactions:
  - `stack.pop`, if empty, retries forever (unsafe, because non-lock-free)
  - `exchanger.exchange`, if no partner found, retries forever (also unsafe)
  - each can be made safe by `.?` (will only try once)
  - however, composing the two is also an option (elimination stack)
  - Can we have an API for composing unsafe parts into something which is safe?
    - e.g., `PartialRxn[A, B]`
    - `.?` would make a (safe) `Rxn` from it
    - `.+(<something safe here>)` would also make it safe
- Think about global / thread-local state:
  - cleanup of unused exchanger stats

## Misc. ideas

- Try building a native image with Graal, to see if it works
- Other data structures:
  - concurrent bag (e.g., https://dl.acm.org/doi/10.1145/1989493.1989550)
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
        - `pop: AsyncRxn[Any, A]`
        - `def unsafeRun(ar: AsyncRxn[A, B], a: A): Either[F[A], A]`
        - `def unsafeToF(ar: AsyncRxn[A, B], a: A): F[A] = unsafeRun(...).fold(x => x, F.pure)`

## Considered ideas

- `LongRef`, `IntRef`, etc.
  - won't work, because we can't store a descriptor in them
- `Ref` which is backed by mmapped memory / JS shared array
  - won't work, because we can't store a descriptor in them
