/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

  val uploadArtifactRef: UseRef.Public =
    UseRef.Public("actions", "upload-artifact", "330a01c490aca151604b8cf639adc76d48f6c5d4") // 5.0.0

  val additionalParams: Map[(String, String), Map[String, String]] = Map(
    ("actions", "checkout") -> Map("persist-credentials" -> "false"),
  )

  val refVersionMapping: Map[(String, String), String] = Map(
    ("actions", "cache") -> "0057852bfaa89a56745cba8c7296529d2fc39830", // 4.3.0; sbt/setup-sbt is pinned to this
    ("actions", "checkout") -> "08c6903cd8c0fde910a37f88322edcfb5dd907a8", // 5.0.0
    ("actions", "setup-java") -> "dded0888837ed1f317902acf8a20df0ad188d165", // 5.0.0
    (uploadArtifactRef.owner, uploadArtifactRef.repo) -> uploadArtifactRef.ref,
    ("sbt", "setup-sbt") -> "3e125ece5c3e5248e18da9ed8d2cce3d335ec8dd", // 1.1.14
    ("scalacenter", "sbt-dependency-submission") -> "64084844d2b0a9b6c3765f33acde2fbe3f5ae7d3", // 3.1.0
  )
}
