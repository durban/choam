/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

/**
 * Extension point for MCAS: an MCAS operation
 * can be executed on any number of objects
 * which conform to this interface.
 *
 * These are the low-level, primitive operations
 * required by the MCAS implementation. They are
 * easily implemented by, e.g., having an
 * `AtomicReference` or similar. (For implementations
 * of this interface, see the `choam-core` module.)
 *
 * Some method names are prefixed by `unsafe` because
 * these are necessarily side-effecting methods,
 * and they're also very low-level. Other methods are
 * not "unsafe", since they're mostly harmless.
 *
 * Generally, this interface should not be used
 * directly. Instead, use MCAS, or an even higher
 * level abstraction.
 */
trait MemoryLocation[A] {

  def unsafeGetVolatile(): A

  def unsafeSetVolatile(nv: A): Unit

  def unsafeCasVolatile(ov: A, nv: A): Boolean

  def unsafeCmpxchgVolatile(ov: A, nv: A): A

  def id0: Long

  def id1: Long

  def id2: Long

  def id3: Long
}

object MemoryLocation {

  def globalCompare(a: MemoryLocation[_], b: MemoryLocation[_]): Int = {
    import java.lang.Long.compare
    if (a eq b) 0
    else {
      val i0 = compare(a.id0, b.id0)
      if (i0 != 0) i0
      else {
        val i1 = compare(a.id1, b.id1)
        if (i1 != 0) i1
        else {
          val i2 = compare(a.id2, b.id2)
          if (i2 != 0) i2
          else {
            val i3 = compare(a.id3, b.id3)
            if (i3 != 0) i3
            else {
              // TODO: maybe AssertionError? Or impossible()?
              throw new IllegalStateException(s"[globalCompare] ref collision: ${a} and ${b}")
            }
          }
        }
      }
    }
  }
}