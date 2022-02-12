/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.Integer.{ remainderUnsigned }
import java.lang.Long.{ hashCode => longHash }

private object BloomFilter extends BloomFilter[MemoryLocation[_]] {

  protected final def leftHash(a: MemoryLocation[_]): Int =
    longHash(a.id0) ^ longHash(a.id1)

  protected final def rightHash(a: MemoryLocation[_]): Int =
    longHash(a.id2) ^ longHash(a.id3)
}

private sealed abstract class BloomFilter[A](
) {

  protected def leftHash(a: A): Int

  protected def rightHash(a: A): Int

  /**
   * Bloom filter with 128 bits (2 `Long`s, m = 128),
   * and 2 hash functions (k = 2), can store this much
   * items (n = 25) with false positive probability 0.1 (this
   * is arbitrarily chosen).
   */
  final def threshold: Int =
    25

  /** Like `threshold`, but probability < 0.001 */
  final def threshold1000: Int =
    2

  final def insertLeft(left: Long, a: A): Long =
    left | (1L << hashL(a))

  final def insertRight(right: Long, a: A): Long =
    right | (1L << hashR(a))

  final def maybeContains(left: Long, right: Long, a: A): Boolean =
    !definitelyNotContains(left, right, a)

  final def definitelyNotContains(left: Long, right: Long, a: A): Boolean = {
    val lh = hashL(a)
    if (isBitSet(left, lh)) {
      val rh = hashR(a)
      if (isBitSet(right, rh)) {
        false
      } else {
        true
      }
    } else {
      true
    }
  }

  private[this] final def hashL(a: A): Int =
    remainderUnsigned(leftHash(a), 64)

  private[this] final def hashR(a: A): Int =
    remainderUnsigned(rightHash(a), 64)

  private[this] final def isBitSet(num: Long, bitNum: Int): Boolean = {
    (num & (1L << bitNum)) != 0
  }
}
