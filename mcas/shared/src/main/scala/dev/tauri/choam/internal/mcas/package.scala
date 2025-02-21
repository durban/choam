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

package object mcas {

  private[choam] final def refHashString(
    id: Long,
  ): String = {
    toHexPadded(refShortHash(id))
  }

  private[choam] final def toHexPadded(n: Long): String = {
    val hex = java.lang.Long.toHexString(n)
    if (hex.length < 16) {
      val padding = "0".repeat(16 - hex.length)
      padding + hex
    } else {
      hex
    }
  }

  private[this] final def refShortHash(id: Long): Long = {
    mix64_13(id) // TODO: when we have pre-hashed IDs, remove this (except for idBase in arrays)
  }

  /** https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html */
  private[this] final def mix64_13(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 30)
    n *= 0xbf58476d1ce4e5b9L
    n ^= (n >>> 27)
    n *= 0x94d049bb133111ebL
    n ^= (n >>> 31)
    n
  }
}
