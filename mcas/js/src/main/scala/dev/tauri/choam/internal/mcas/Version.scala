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

package dev.tauri.choam
package internal
package mcas

// Note: this class/object is duplicated for JVM/JS
object Version {

  final val Start = Long.MinValue
  final val BoxedStart: java.lang.Long = java.lang.Long.valueOf(Start)
  final val Incr = 1L
  final val None = Long.MaxValue

  final val Active = None - 1L
  final val Successful = None - 2L
  final val FailedVal = None - 3L
  final val Reserved = None - 4L
  // FailedVer = any valid version
}
