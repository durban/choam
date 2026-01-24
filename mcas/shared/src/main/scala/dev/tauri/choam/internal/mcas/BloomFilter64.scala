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

import scala.util.hashing.byteswap32

/**
 * 64-bit wide Bloom filter, stores `Int`s
 *
 * Parameters:
 * `m` (the number of bits in the filter) is 64,
 * so exactly one `Long` is used.
 * `k` (the number of hash functions) is 4, we
 * use 4 separate 6-bit wide sections of the
 * (hashed) `Int`s.
 *
 * The number of items added to the set (`n`) is unknown,
 * but expected to be at most the number of physical
 * threads, and is usually far less. E.g., if it's
 * 8, then we get a false positive probability of less
 * than 2.5%.
 */
private[mcas] object BloomFilter64 {

  final def insert(set: Long, item: Int): Long = {
    set | hashItem(item)
  }

  /**
   * If `item` is definitely absent from the `set`,
   * insert it, and return the new set. If `item` is
   * possibly already in the `set`, return `0` (this
   * is fine, because after inserting any item, the
   * set is definitely not `0`, because at least 1
   * bit is set).
   */
  final def insertIfAbsent(set: Long, item: Int): Long = {
    val h = hashItem(item)
    if ((set & h) != h) {
      // definitely absent:
      set | h
    } else {
      // possibly present:
      0L
    }
  }

  final def maybeContains(set: Long, item: Int): Boolean = {
    !definitelyAbsent(set, item)
  }

  final def definitelyAbsent(set: Long, item: Int): Boolean = {
    val h: Long = hashItem(item)
    (set & h) != h
  }

  final def estimatedSize(set: Long): Int = {
    import java.lang.Math.log
    val x: Double = java.lang.Long.bitCount(set).toDouble
    val estimate = -((64.0 / 4.0) * log(1.0 - (x / 64.0)))
    estimate.toInt
  }

  @inline
  private[this] final def hashItem(item: Int): Long = {
    val h = mix(item)
    val h1 = (h >>> 26) // top 6 bits
    val h2 = (h >>> 20) & 63 // next 6 bits
    val h3 = (h >>> 14) & 63 // next 6 bits
    val h4 = (h >>>  8) & 63 // next 6 bits
    (1L << h1) | (1L << h2) | (1L << h3) | (1L << h4)
  }

  @inline
  private[this] final def mix(item: Int): Int = {
    // We don't trust `System.identityHashCode` completely;
    // it's typically less than 32 bits, and we don't know
    // where the zeros are, so we're mixing it a little bit:
    byteswap32(item)
    // TODO: maybe MurmurHash3.finalizeHash(MurmurHash3.mixLast(0x9E3779B9, item), 0)
  }
}
