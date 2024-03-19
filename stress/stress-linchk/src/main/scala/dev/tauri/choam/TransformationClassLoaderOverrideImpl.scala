/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam

object TransformationClassLoaderOverrideImpl {

  /**
   * Additional classes we don't want to transform
   * (the default implementation in lincheck excludes
   * most of `kotlin.*`, but doesn't know about `scala.*`).
   */
  final def doNotTransform(className: String): Boolean = {
    val mp = "dev.tauri.choam.internal.mcas."
    val grig = mp + "GlobalRefIdGen"
    val rig = mp + "RefIdGen"
    if (
      className.startsWith("scala.") ||
      // TODO: figure out why it doesn't work with these:
      className.startsWith(grig) ||
      className.startsWith(rig)
    ) {
      true
    } else {
      false
    }
  }
}
