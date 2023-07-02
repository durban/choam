/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.math.Ordering

private final class MemoryLocationOrdering[A]
  extends Ordering[MemoryLocation[A]] {

  final override def compare(x: MemoryLocation[A], y: MemoryLocation[A]): Int = {
    this.globalCompare(x, y)
  }

  private[this] final def globalCompare(a: MemoryLocation[_], b: MemoryLocation[_]): Int = {
    import java.lang.Long.{ compare => lcompare }
    if (a eq b) 0
    else {
      val i0 = lcompare(a.id0, b.id0)
      if (i0 != 0) i0
      else {
        val i1 = lcompare(a.id1, b.id1)
        if (i1 != 0) i1
        else {
          val i2 = lcompare(a.id2, b.id2)
          if (i2 != 0) i2
          else {
            val i3 = lcompare(a.id3, b.id3)
            if (i3 != 0) i3
            else {
              impossible(s"[globalCompare] ref collision: ${a} and ${b}")
            }
          }
        }
      }
    }
  }
}