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
- `dev.tauri.choam.data.TtrieModelTest` fails; seems to be a lincheck bug.
- `SkipListModelTest` sometimes fails; it's unclear if this is just a timeout, but seems likely:
  ```
  ==> X dev.tauri.choam.skiplist.SkipListModelTest.Model checking test of SkipListMap  288.505s org.jetbrains.kotlinx.lincheck.LincheckAssertionError:
  = The execution has hung, see the thread dump =
  Execution scenario (init part):
  [insert(0, Gx), remove(-1), lookup(0), remove(1), remove(-1)]
  Execution scenario (parallel part):
  | insert(0, uPI)  | remove(-2)        |
  | insert(0, Rw7N) | insert(2, Kjq4)   |
  | lookup(-3)      | insert(-3, ygZVM) |
  | remove(3)       | lookup(-3)        |
  Execution scenario (post part):
  [remove(-4), remove(-3), insert(-1, NTGQz)]
  Thread-0:
    java.lang.Thread.run(Thread.java:829)
  Thread-1:
    jdk.internal.misc.Unsafe.park(Native Method)
    java.util.concurrent.locks.LockSupport.park(LockSupport.java:323)
    java.lang.Thread.run(Thread.java:829)

      at org.jetbrains.kotlinx.lincheck.LinChecker.check(LinChecker.kt:38)
      at org.jetbrains.kotlinx.lincheck.LinChecker$Companion.check(LinChecker.kt:197)
      at org.jetbrains.kotlinx.lincheck.LinChecker.check(LinChecker.kt)
      at dev.tauri.choam.skiplist.SkipListModelTest.$anonfun$new$1(SkipListModelTest.scala:40)
      at dev.tauri.choam.BaseLinchkSpec.$anonfun$test$1(BaseLinchkSpec.scala:33)
  ```

## Other improvements

- Testing:
  - JCStress:
    - `Exchanger`
    - replacing descriptors (weakref?)
    - Other things (Promise? delayComputed?)
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
  - Rename unstable (i.e., no bincompat) packages:
    - dev.tauri.choam.skiplist -> dev.tauri.choam.internal.skiplist (impl. detail)
    - dev.tauri.choam.vhandle -> ... (impl. detail)
    - dev.tauri.choam.mcas -> ... (API is not good enough to promise stability)
    - others?
  - Document compatibility
    - binary compatibility and versioning
      - note that between choam modules, exact version match is needed
    - supported platforms (JVM 11+ and scala-js for now)
      - either `Windows-PRNG`, or (`/dev/random` and `/dev/urandom`) must be available
    - Scala 2.13 and 3.3
    - \*.internal.\* packages (no bincompat)
    - `unafe` APIs (no bincompat)
    - `laws` module (no bincompat)
    - assumptions:
      - for lock freedom:
        - `VarHandle` operations are lock-free (this means 64-bit platforms, due to `long`)
        - no infinite loop `Rxn`s
        - no `unsafe`
        - we ignore GC and classloading locks
        - `UUIDGen[Axn]` and `Random[Axn]` may use the OS RNG, which might block
        - we assume `ThreadLocalRandom` is lock-free (why wouldn't it be?)
        - `Strategy.Spin` is the only lock-free `Strategy`
        - operations in `F[_]` are (obviously) might not be lock-free (e.g., `Promise#get`)
        - we assume calling a CE `async` callback is lock-free (in CE 3 IO it is not!)
        - only `Mcas.DefaultMcas` (i.e., `Emcas`) is lock-free
      - for scala-js:
        - we assume single-threaded execution (TODO: what about wasm?)
  - MCAS API review
    - is it usable outside of `choam`?
    - if not, it doesn't really make sense to have it in a separate module
      - being in the same module would simplify using `ThreadContext` for `Rxn`-things
  - Rename `flatMapF`
    - maybe `semiFlatMap` (or `semiflatMap`?)
    - or `subflatMap`?
  - Rxn.delay?
    - allocating:
      - `Ref` (most others are built on this)
      - `Ref.array`
      - `Exchanger`
    - calling async callbacks:
      - only `Promise` really needs it
      - `[Gen]WaitList` (as an optimization, to avoid `Promise`)
    - other special cases:
      - `UUIDGen`
      - `Unique`
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
      - (maybe: do not guarantee any behavior for now)
    - Transient errors can sometimes be handled with `+` (`Choice`)
      - but sometimes this can cause infinite retry
- Cancellation support
  - `Thread.interrupt` (done)
  - cats-effect cancellation?
    - see `IOCancel` for a few attempts
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
  - ttrie-set(?)
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
