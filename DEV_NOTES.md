<!--

   SPDX-License-Identifier: Apache-2.0
   Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt

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

## Release process

1. Commit every change.
1. Push any new commits.
1. Wait for CI to become green.
1. Tag the release (e.g., `git tag -s "v1.2.3"`), but don't push the new tag.
1. In `sbt`, call `release` (requires Sonatype credentials).
1. If everything looks right, push the new tag (`git push --tags`).
