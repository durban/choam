/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.typelevel.sbt.gha.UseRef

object GhActions {

  val refVersionMapping: Map[(String, String), String] = Map(
    ("actions", "checkout") -> "11bd71901bbe5b1630ceea73d27597364c9af683", // 4.2.2
    ("actions", "setup-java") -> "c5195efecf7bdfc987ee8bae7a71cb8b11521c00", // 4.7.1
    ("actions", "upload-artifact") -> "ea165f8d65b6e75b540449e92b4886f43607fa02", // 4.6.2
    ("scalacenter", "sbt-dependency-submission") -> "f3c0455a87097de07b66c3dc1b8619b5976c1c89", // 2.3.1
    ("sbt", "setup-sbt") -> "26ab4b0fa1c47fa62fc1f6e51823a658fb6c760c", // 1.1.7
  )
}
