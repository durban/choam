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

# TODO

## Bugs

- `Ctrie` is incomplete
  - no `remove` (+ tombstone handling)
  - even if completed, it would not be very good
    - to have composability, the root will always have to be validated
    - this would make it not scalable (the root ref is very contended)
- Can't run benchmarks with Scala 3
- CI: Tests sometimes time out
  - probably due to GC pauses
- CI: ExchangerSpec often times out with ZIO
- CI failure (ubuntu, graal, scala 2.13):
  ```
  ==> X dev.tauri.choam.ExchangerSpecJvm_EMCAS_BIO.A StatMap must persist between different unsafePerform runs  0.22s munit.FailException: /home/runner/work/choam/choam/data/jvm/src/test/scala/dev/tauri/choam/ExchangerSpecJvm.scala:418 assertion failed
  417:          assert(ctx2.supportsStatistics)
  418:          assert(ctx2.getStatistics().contains(ex2.key))
  419:        } else {
  ```

## Other improvements

- Testing:
  - JCStress:
    - `Exchanger`
    - replacing descriptors (weakref?)
    - Other things (Promise? delayComputed?)
    - Separate tests to `quick` and `slow`
    - Run `quick` tests in CI
  - LawsSpec:
    - improve generated `Rxn`s, check if they make sense
    - check if `testingEqRxn` makes sense
  - Test with other IO impls (when they support ce3)
- Optimization ideas:
  - Exchanger: there is a lot of `Array[Byte]` copying
  - Reducing allocations (we're allocating _a lot_)
    - EMCAS (maybe reusing descriptors?)
    - Rxn
      - lots of `Rxn` instances
      - `ObjStack.Lst`
  - `null` checking:
    - in theory, the following are all the same (`x : AnyRef`):
      - `if (x eq null) ... else ...`
      - `if (x == null) ... else ...`
      - `x match { case null => ...; case _ => ... }`
      - (and similarly for `ne` and `!=`)
    - refs:
      - both Scala 2.13 and 3.1 generates code with `ifnull`/`ifnonnull`
      - https://github.com/scala/bug/issues/570#issuecomment-292349095
      - https://github.com/scala/bug/issues/3195
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
  - integration with FS2 (`choam-stream`):
    - Channel?
    - Optimize SignallingRef
- API cleanup:
  - MCAS API review
    - e.g., KCAS is still mentioned a lot
    - is it usable outside of `choam`?
  - Rename `flatMapF`
    - maybe `semiFlatMap` (or `semiflatMap`?)
    - or `subflatMap`?
  - Rxn.delay?
    - allocating (but: only `Ref` really needs it, others are built on that)
    - calling async callbacks (but: only `Promise` needs it, others don't)
    - allocating `Exchanger` (this is similar to `Ref`)
    - allocating `Ref.array` (this is similar to `Ref`)
    - other special cases:
      - `UUIDGen`
      - `Unique`
      - `cats.effect.std.Random`
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
    - Transient errors can sometimes be handled with `+` (`Choice`)
      - but sometimes this can cause infinite retry
- Cancellation support
  - `Thread.interrupt` (done)
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
    - `.+(<something safe here>)` would also make it safe
- Think about global / thread-local state:
  - cleanup of unused contexts
  - cleanup of unused exchanger stats

## Misc.

- Try building a native image with Graal, to see if it works
- `LongRef`, `IntRef`, ... (benchmarks needed, it might not make sense)
  - especially since we can't store a descriptor in, e.g., a real `AtomicLong`
- `Ref` which is backed by mmapped memory(?)
  - similar: mmapped from _persistent_ memory
  - similar: JS shared array
    - this would break assumptions the default JS MCAS relies on (single threaded)
  - but, a problem with all these: we can't write a descriptor into them!
  - see also: JEP 412 (https://openjdk.java.net/jeps/412)
- Other data structures:
  - ctrie-set (but see problems with Ctrie)
  - `SkipListMap`, `SkipListSet`
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
