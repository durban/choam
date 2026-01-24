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

package dev.tauri.choam
package internal
package mcas

import java.security.{ SecureRandom => JSecureRandom }

private[choam] object OsRng extends OsRngPlatform

private[choam] abstract class OsRng {

  def nextBytes(dest: Array[Byte]): Unit

  def close(): Unit

  /** For testing; `None` means "not applicable" */
  private[choam] def isClosed(): Option[Boolean]

  final def nextBytes(n: Int): Array[Byte] = {
    require(n >= 0)
    val dest = new Array[Byte](n)
    if (n > 0) {
      nextBytes(dest)
    }
    dest
  }
}

private abstract class AdaptedOsRng(underlying: JSecureRandom) extends OsRng {

  final override def nextBytes(dest: Array[Byte]): Unit = {
    if (dest.length > 0) {
      underlying.nextBytes(dest)
    }
  }

  final override def close(): Unit = {
  }

  final override def isClosed(): Option[Boolean] = {
    None
  }
}
