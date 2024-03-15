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

import RefIdGenBase.GAMMA

// TODO: move this to `mcas` (it's not EMCAS-specific)
private[choam] final class RefIdGenerator extends RefIdGenBase { // TODO: rename to RefIdGen

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

  /** Returns idBase for RefArrays */ // TODO: is ID overflow plausible with big arrays?
  final def allocateArrayBlockWithoutThreadLocal(size: Int): Long = {
    this.allocateThreadLocalBlock(size)
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
    n * GAMMA
  }
}

private[choam] object RefIdGenerator {

  /** The computed ID must've been already allocated in a block! */
  final def compute(base: Long, offset: Int): Long = {
    (base + offset.toLong) * GAMMA
  }

  final class ThreadLocalRefIdGenerator private[RefIdGenerator] (
    private[this] val parent: RefIdGenerator,
    private[this] var next: Long,
    private[this] var remaining: Int,
    private[this] var nextBlockSize: Int,
  ) {

    private[this] final val maxBlockSize =
      1 << 30

    @tailrec
    final def nextId(): Long = {
      val rem = this.remaining
      if (rem > 0) {
        val n = this.next
        this.next = n + 1L
        this.remaining = rem - 1
        n * GAMMA
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

    final def allocateArrayBlock(size: Int): Long = {
      require(size > 0)
      val rem = this.remaining
      if (rem >= size) {
        val base = this.next
        this.next = base + size.toLong
        this.remaining = rem - size
        base
      } else {
        // Not enough IDs in the current thread-local
        // block. But instead of allocating a new one,
        // we just fulfill this request from the global.
        // (Because this way, we don't leak the remaining
        // IDs in our thread-local block.)
        this.parent.allocateArrayBlockWithoutThreadLocal(size)
      }
    }
  }
}
