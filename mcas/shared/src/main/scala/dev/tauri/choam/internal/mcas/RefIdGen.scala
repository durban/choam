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

import java.util.concurrent.atomic.AtomicLong

import RefIdGenBase.GAMMA

/**
 * `RefIdGen` generates `Long` IDs for `Ref`s in a way which
 * guarantees uniqueness (which is a must for `Ref`s) and
 * scales with the number of cores (so that ID generation is
 * not a bottleneck for `Rxn`s which allocate a lot of `Ref`s).
 *
 * The idea is that every thread has its own `ThreadLocalRefIdGen`,
 * which has a "preallocated block" of IDs. As long as possible,
 * it returns IDs from that block. This should be very fast,
 * because it is entirely thread-local. When the block is
 * exhausted, it requests a new block from the global (shared)
 * `RefIdGen`.
 *
 * The sizing of these thread-local blocks should take into
 * account performance: big blocks are good, because it means
 * that we're mostly working thread-locally. However, if a
 * thread is abandoned, its remaining preallocated IDs in its
 * current block are "wasted" or "leaked". If we waste too much,
 * we could overflow the global `ctr`. (Abandoning "real" (i.e.,
 * platform) threads is probably not a concern, however on
 * newer JVMs we also have virtual threads. These are designed
 * to be used shortly, then be abandoned. And they're very
 * fast to create.) So very big blocks are not good.
 *
 * So, to avoid overflow, every `ThreadLocalRefIdGen` starts from
 * a small block, and doubles the size with every new block
 * (up to a limit). This way every thread only wastes at most
 * half of the IDs it preallocates. So even in the worst
 * case (if we create a lot of virtual threads, and each
 * of them is abandoned at the worst possible time) we only
 * waste half of the IDs. That means we still have 63 bits,
 * which should be plenty. (The doubling also means, that threads
 * which create a lot of `Ref`s quickly get up to big blocks.)
 *
 * Generated IDs should not be contiguous, because we'd like
 * to put them into a hash-map, hash-trie or similar. (Of course
 * we could hash them later, but we'd rther do this once on
 * generation.) So we use Fibonacci hashing to generate a
 * "good" distribution.
 */
private[choam] final class RefIdGen private[mcas] () extends RefIdGenBase {

  private[this] final val initialBlockSize =
    2 // TODO: maybe start with bigger for platform threads?

  private[this] val ctr =
    new AtomicLong(java.lang.Long.MIN_VALUE) // TODO: VarHandle, padding

  private[RefIdGen] final def allocateThreadLocalBlock(size: Int): Long = {
    require(size > 0)
    val s = size.toLong
    val n = this.ctr.getAndAdd(s) // TODO: opaque
    assert(n < (n + s)) // ID overflow
    n
  }

  final def newThreadLocal(): RefIdGen.ThreadLocalRefIdGen = {
    new RefIdGen.ThreadLocalRefIdGen(
      parent = this,
      next = 0L, // unused, because:
      remaining = 0, // initially no more remaining
      nextBlockSize = initialBlockSize,
    )
  }

  /** Returns idBase for RefArrays */ // TODO: is ID overflow plausible with big arrays?
  final def nextArrayIdBaseGlobal(size: Int): Long = {
    this.allocateThreadLocalBlock(size)
  }

  /**
   * Slower fallback to still be able to generate
   * an ID when we don't have access to a thread-
   * local context.
   */
  final def nextIdGlobal(): Long = {
    val n = this.ctr.getAndIncrement() // TODO: opaque
    assert(n < (n + 1L)) // ID overflow
    n * GAMMA
  }
}

private[choam] object RefIdGen {

  /** The computed ID must've been already allocated in a block! */
  final def compute(base: Long, offset: Int): Long = {
    (base + offset.toLong) * GAMMA
  }

  final class ThreadLocalRefIdGen private[RefIdGen] (
    private[this] val parent: RefIdGen,
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
        // next time we'll succeed for sure:
        this.nextId()
      }
    }

    final def nextArrayIdBase(size: Int): Long = {
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
        // TODO: This is not exactly optimal if we're
        // TODO: at the end of a large(ish) block, and
        // TODO: need to allocate a lot of small arrays
        // TODO: which do not fit in the remaining,
        // TODO: because then we'll always go to global.
        // TODO: But also, FAA is cheap...
        this.parent.nextArrayIdBaseGlobal(size)
      }
    }
  }
}
