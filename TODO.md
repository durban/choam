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

- EMCAS: What happens when a thread dies during an op? Descriptors
  could be freed then, but there is no final value!
  - When starting a k-CAS op (but after sorting), store the whole
    descriptor in a thread-local context.
  - All these thread contexts are in a global "map".
  - If the thread finishes (either fails or succeeds), remove the
    descriptor from the thread context.
  - If the thread dies before finishing, descriptors won't be
    collected by the GC, because the context still holds them.
  - (If another thread finalizes the op, it could clear the context,
    however this should be done carefully, as it's another thread's
    context.)
  - Prerequisite: a way of passing thread-local contexts (requires
    changes to `React`.)
- EMCAS: When CAS-ing from a weak data to another, we lose the original
  weakref. Later, the new one could be cleared, and detached; however
  the old one could still be in use. This is unsafe.

## Other improvements

- Optimization ideas:
  - Boxing
  - React interpreter (external interpreter?)
  - Review writes/reads in EMCAS, check if we can relax them
  - Check if weakrefs affect performance.
- Cleanup:
  - IBR
  - Review benchmarks, remove useless ones
- Finish Ctrie
- Scala 3:
  - Port tests to munit
  - Macro annotations (JcStressMacros): no replacement; we'll have to
    ignore NaiveKCAS for these, and only run stress tests for EMCAS.
