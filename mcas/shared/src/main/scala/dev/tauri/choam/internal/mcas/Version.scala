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

object Version {

  /** The initial version of every ref */
  final val Start = Long.MinValue // Note: this is copied to VersionJ.java

  final val BoxedStart: java.lang.Long = java.lang.Long.valueOf(Start)

  final val Incr = 1L

  /** An invalid version constant */
  final val None = Long.MaxValue

  final val Active = None - 1L // Note: this is copied to VersionJ.java
  final val Successful = None - 2L
  final val FailedVal = None - 3L
  final val Reserved = None - 4L // Note: this is copied to VersionJ.java
  // FailedVer = any valid version
}
