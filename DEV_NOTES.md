<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt

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

# Notes for development

Unorganized notes which might be useful during development.

## Releasing

### Release process for tagged versions

These are the "regular" versions, e.g., `0.4.0` or `0.4.0-RC1`.

1. Commit every change.
1. Make sure there are no untracked files in git.
1. Push any new commits.
1. Wait for CI to become green.
1. Tag the release (e.g., `git tag -s "v1.2.3"`), but don't push the new tag.
1. Start `sbt`, and:
   - `clean`
   - `;staticAnalysis;++3.3.6;staticAnalysis` (precompile seems to help publish faster)
   - `exit`
1. In a new `sbt` shell:
   - `release` (this step requires Sonatype credentials)
1. If everything looks right, push the new tag (`git push --tags`).
1. Create a "release" on github for the new tag.

### Release process for "hash" versions

These are "preview" versions, e.g., `0.4-39d987a` or `0.4.3-2-39d987a`.

1. Commit every change.
1. Make sure there are no untracked files in git.
1. Push any new commits.
1. In `sbt`, call `releaseHash` (requires Sonatype credentials).

## Development

### Lincheck

A warning like this appears when running tests in `stressLinchk`:

> OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended

According to https://stackoverflow.com/a/57957031, this is harmless.

## Historical decisions

Older versions used to have (sometimes significantly) different API and internals.
Some of the decisions to change them are documented here.

### API

- The `choam-mcas` module used to be public, and the MCAS algorithm was configurable.
  Now it is private, as (primarily due to the changes necessary to support opacity)
  it is not anymore a simple and clean MCAS library, but somewhat intertwined with
  higher-level (`Rxn`) concerns. The algorithm is not selectable, as EMCAS is clearly
  the best (in speed and scalability). In fact, CASN was removed (`SpinLockMcas` remains,
  because it is so simple, that it's not a burden to maintain it, and might be useful
  for testing).

### Internals

- EMCAS used to employ an IBR (interval-based reclamation) scheme to determine if a
  descriptor is still in use by a helper. This was replaced by using the JVM GC for
  this purpose (by using weakrefs). This solution proved to be faster (although it
  was necessary to "reuse" weakrefs to avoid having too much of them, because that
  slows down the GC; see `getReusableMarker`/`getReusableWeakRef`).
- The `Rxn` interpreter used to `match` on `Int` tags (JVM `tableswitch`). This was
  inspired by an old optimization in the Scala compiler for matching on sealed
  subclasses (see
  <https://github.com/scala/scala/commit/b98eb1d74141a4159539d373e6216e799d6b6dcd>).
  The Cats Effect runloop is doing something very similar, and ZIO also used to do
  something like this in version 1. This was removed from `Rxn`, and now it's a
  simple `match` on a `sealed` type. This way it's easier to maintain (e.g., we get
  non-exhaustive match warnings), and some benchmarking showed that it might even
  be slightly faster this way.
- An even older version of `Rxn` was based on calling continuations recursively
  (like in <https://github.com/aturon/ChemistrySet>). That was not stack-safe,
  so it was changed to a stack-safe interpreter.
