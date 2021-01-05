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

# TODO

## Bugs

- ???

## Other improvements

- Testing:
  - Figure out some tricky race conditions, and test them with JCStress.
  - LawsSpec:
    - improve generated `React`s, check if they make sense
    - check if `testingEqReact` makes sense, maybe do structural checking
- EMCAS with simplified IBR:
  - Try to enable cleanup after a k-CAS op is finalized.
    - Measure performance.
    - Measure memory requirements, make sure finalized list is not too big.
- Compile-time detection of impossible k-CAS operations
- Optimization ideas:
  - Boxing
  - React interpreter (external interpreter?)
  - Review writes/reads in EMCAS, check if we can relax them
  - Check if weakrefs affect performance.
- Cleanup:
  - IBR
  - Review benchmarks, remove useless ones
- Finish Ctrie
- Scala 3
- ce3
- API cleanup:
  - separate unsafe/low-level API for `invisibleRead` and other dangerous
  - move `KCAS` into separate JAR, figure out proper API

## Misc.

- `LongRef`, `IntRef`, ... (benchmarks needed, it might not make sense)
- `Ref` which is backed by mmapped memory(?)
- Other data structures:
  - `AsyncQueue`