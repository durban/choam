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

import java.util.concurrent.atomic.AtomicReference
import java.security.{ SecureRandom => JSecureRandom }

private[choam] object OsRng extends OsRngPlatform {

  private[this] final val _global: AtomicReference[OsRng] = // TODO:0.5: remove
    new AtomicReference

  /** Only for testing! */
  private[choam] final def globalUnsafe(): OsRng =
    this._global.get()

  /** Only for testing! */
  private[choam] final def globalLazyInit(): OsRng = {
    this._global.get() match {
      case null =>
        // Under certain circumstances (e.g.,
        // Linux right after boot in a
        // fresh VM), this call might block.
        // We really can't do anything about
        // it, and that's why this if for
        // testing only.
        val nv = this.mkNew()
        val wit = this.compareAndExchange(this._global, null, nv)
        if (wit eq null) nv
        else wit
      case ov =>
        ov
    }
  }
}

private[choam] abstract class OsRng {

  def nextBytes(dest: Array[Byte]): Unit

  def nextBytes(n: Int): Array[Byte] = {
    require(n >= 0)
    val dest = new Array[Byte](n)
    if (n > 0) {
      nextBytes(dest)
    }
    dest
  }
}

private class AdaptedOsRng(underlying: JSecureRandom) extends OsRng {

  final override def nextBytes(dest: Array[Byte]): Unit = {
    if (dest.length > 0) {
      underlying.nextBytes(dest)
    }
  }
}
