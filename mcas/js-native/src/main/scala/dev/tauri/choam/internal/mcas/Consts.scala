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

// Note: this class/object is duplicated for JVM and JS/Native
object Consts {

  @inline
  final val OPTIMISTIC =
    1L

  @inline
  final val PESSIMISTIC =
    0L

  @inline
  final val InvalidListenerId =
    java.lang.Long.MIN_VALUE

  @inline
  final val statsEnabledProp =
    "dev.tauri.choam.stats"

  @inline
  final val statsEnabled =
    CompileTimeSystemProperty.getBoolean(statsEnabledProp)

  @inline
  final val mcasImplProp =
    "dev.tauri.choam.internal.mcas.impl"

  @inline
  final val mcasImpl =
    CompileTimeSystemProperty.getString(mcasImplProp)

  /**
   * Next power of 2 which is `>= x`.
   *
   * `clp2` from Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  @inline final def nextPowerOf2(x: Int): Int = {
    jsAssert((x > 0) && (x <= (1 << 30))) // scala-js is sometimes weird with arrays, so we leave this here
    0x80000000 >>> (Integer.numberOfLeadingZeros(x - 1) - 1)
  }

  /** https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html */
  final def staffordMix13(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 30)
    n *= 0xbf58476d1ce4e5b9L
    n ^= (n >>> 27)
    n *= 0x94d049bb133111ebL
    n ^= (n >>> 31)
    n
  }

  /** https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html */
  final def staffordMix04(s: Long): Long = {
    var n: Long = s
    n ^= (n >>> 33)
    n *= 0x62a9d9ed799705f5L
    n ^= (n >>> 28)
    n *= 0xcb24d0a5c88c35b3L
    n ^= (n >>> 32)
    n
  }
}
