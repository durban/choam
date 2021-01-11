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

- ???

## Other improvements

- Testing:
  - Figure out some tricky race conditions, and test them with JCStress.
  - LawsSpec:
    - improve generated `React`s, check if they make sense
    - check if `testingEqReact` makes sense, maybe do structural checking
  - Test with other IO impls (when they cupport ce3)
- EMCAS with simplified IBR:
  - Try to enable cleanup after a k-CAS op is finalized.
    - Measure performance.
    - Measure memory requirements, make sure finalized list is not too big.
- Compile-time detection of impossible k-CAS operations
- Optimization ideas:
  - Boxing
  - React interpreter (external interpreter?)
  - Review writes/reads in EMCAS, check if we can relax them
- Cleanup:
  - Review benchmarks, remove useless ones
  - Remove `ArrCas`
  - Remove old `upd`, use `updImproved` instead
- Finish Ctrie
- Scala 3
- ce3:
  - Can `React` implement `MonadCancel`? (Would need error handling)
- Async (`choam-async`):
  - improve `Promise` API (see `Deferred` in cats-effect)
  - `AsyncQueue`
  - integration with FS2? (`choam-stream`)
- API cleanup:
  - separate unsafe/low-level API for `invisibleRead` and other dangerous
    - (unsafe) thread-confined mode for running a `React` (with `NaiveKCAS` or something even more simple)
  - move `KCAS` into separate JAR, figure out proper API (`choam-kcas` or `choam-mcas`)
  - compare with `Ref` in cats-effect: similar things should have similar names
  - Does it make sense to have `React[A, B]` instead of `A => React[B]`?
    - Yes, there is a performance advantage (see `ArrowBench`).
    - We could still make an alias, e.g., `RTask[A] = React[Unit, A]`.
  - Create an equivalent of `cats.effect.Ref#access`
    - This would be a safe version of an `invisibleRead` followed by `cas`.
- Cancellation support
  - `Thread.interrupt`
  - cats-effect cancellation?

## Misc.

- `LongRef`, `IntRef`, ... (benchmarks needed, it might not make sense)
- `Ref` which is backed by mmapped memory(?)
- Other data structures:
  - ctrie-set
  - `SkipListMap`, `SkipListSet`
- "Laws" for the `React` combinators, e.g.:
  - choice prefers the first option
  - `flatMap` == `>>>` and `computed`
  - ...
