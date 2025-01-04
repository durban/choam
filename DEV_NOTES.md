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

Unorganized notes which may be useful during development.

## Release process (for tagged versions)

These are the "regular" versions, e.g., `0.4.0` or `0.4.0-RC1`.

1. Commit every change.
1. Make sure there are no untracked files in git.
1. Push any new commits.
1. Wait for CI to become green.
1. Tag the release (e.g., `git tag -s "v1.2.3"`), but don't push the new tag.
1. In `sbt`, call `release` (requires Sonatype credentials).
1. If everything looks right, push the new tag (`git push --tags`).
1. Create a "release" on github for the new tag.

## Release process for "hash" versions

These are "preview" versions, e.g., `0.4-39d987a` or `0.4.3-2-39d987a`.

1. Commit every change.
1. Make sure there are no untracked files in git.
1. Push any new commits.
1. In `sbt`, call `releaseHash` (requires Sonatype credentials).

## Lincheck

A warning like this appears when running tests in `stressLinchk`:

> OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended

According to https://stackoverflow.com/a/57957031, this is harmless.
