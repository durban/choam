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
package internal
package mcas

import scala.math.Ordering

private final class MemoryLocationOrdering[A]
  extends Ordering[MemoryLocation[A]] {

  final override def compare(x: MemoryLocation[A], y: MemoryLocation[A]): Int = {
    this.globalCompare(x, y)
  }

  /**
   * We don't really care HOW the `MemoryLocation`s are
   * ordered; we just need a total global order. So we
   * order them the way they're naturally in the `Hamt`
   * or `MutHamt`.
   */
  private[this] final def globalCompare(a: MemoryLocation[_], b: MemoryLocation[_]): Int = {
    // We're essentially reimplementing here
    // how `Hamt`/`MutHamt` compares hashes:
    val ah = a.id
    val bh = b.id

    @tailrec
    def go(shift: Int): Int = {
      val r = Integer.compare(logicalIdx(ah, shift), logicalIdx(bh, shift))
      if (r != 0) {
        r
      } else {
        go(shift + 6)
      }
    }

    def logicalIdx(hash: Long, shift: Int): Int = {
      ((hash << shift) >>> 58).toInt
    }

    if (a eq b) {
      0
    } else if (ah == bh) {
      impossible(s"[globalCompare] ref collision: ${a} and ${b}")
    } else {
      go(0)
    }
  }
}
