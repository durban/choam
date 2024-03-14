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
package emcas

import java.util.concurrent.atomic.AtomicLong

private[mcas] final class RefIdGenerator {

  // TODO: this is duplicated below
  private[this] final val gamma =
     0x9e3779b97f4a7c15L // Fibonacci hashing

  private[this] final val initialBlockSize =
    2 // TODO: maybe start with bigger for platform threads?

  private[this] val ctr =
    new AtomicLong(java.lang.Long.MIN_VALUE) // TODO: VarHandle, padding

  private[RefIdGenerator] final def allocateThreadLocalBlock(size: Int): Long = {
    require(size > 0)
    val s = size.toLong
    val n = this.ctr.getAndAdd(s)
    assert(n < (n + s)) // ID overflow
    n
  }

  final def newThreadLocal(): RefIdGenerator.ThreadLocalRefIdGenerator = {
    new RefIdGenerator.ThreadLocalRefIdGenerator(
      parent = this,
      next = 0L, // unused, because:
      remaining = 0, // initially no more remaining
      nextBlockSize = initialBlockSize,
    )
  }

  /**
   * Slower fallback to still be able to generate
   * an ID when we don't have access to a thread-
   * local context.
   */
  final def nextIdWithoutThreadLocal(): Long = {
    val n = this.ctr.getAndIncrement()
    assert(n < (n + 1L)) // ID overflow
    n * gamma
  }
}

private[mcas] object RefIdGenerator {

  final class ThreadLocalRefIdGenerator private[RefIdGenerator] (
    private[this] val parent: RefIdGenerator,
    private[this] var next: Long,
    private[this] var remaining: Int,
    private[this] var nextBlockSize: Int,
  ) {

    private[this] final val gamma =
      0x9e3779b97f4a7c15L // Fibonacci hashing

    private[this] final val maxBlockSize =
      1 << 30

    @tailrec
    final def nextId(): Long = {
      val rem = this.remaining
      if (rem > 0) {
        val n = this.next
        this.next = n + 1
        this.remaining = rem - 1
        n * gamma
      } else {
        val s = this.nextBlockSize
        this.next = this.parent.allocateThreadLocalBlock(s)
        this.remaining = s
        if (s < maxBlockSize) {
          this.nextBlockSize = s << 1
        }
        this.nextId()
      }
    }
  }
}
