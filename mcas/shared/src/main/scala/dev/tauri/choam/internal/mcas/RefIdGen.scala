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
 * (so half of the whole ID space) which should be plenty.
 * (The doubling also means, that threads which create a lot
 * of `Ref`s quickly get up to big blocks.)
 *
 * Allocating whole blocks for `Ref.Array`s makes this a little
 * worse, but we still have more than one-third of the whole
 * 64-bit space (see comment in `nextArrayIdBase`).
 *
 * Generated IDs should not be contiguous, because we'd like
 * to put them into a hash-map, hash-trie or similar. (Of course
 * we could hash them later, but we'd rather do this once on
 * generation.) So we use Fibonacci hashing to generate a
 * "good" distribution.
 */
private[choam] sealed trait RefIdGen {
  def nextId(): Long
  def nextArrayIdBase(size: Int): Long
}

private[choam] object RefIdGen {

  // TODO: create benchmark for scaling with threads

  val global: GlobalRefIdGen = // TODO: instead of this, have a proper acq/rel Runtime
    new GlobalRefIdGen

  /** The computed ID must've been already allocated in a block! */
  final def compute(base: Long, offset: Int): Long = {
    (base + offset.toLong) * GAMMA
  }
}

private[choam] final class GlobalRefIdGen private[mcas] () extends RefIdGenBase with RefIdGen {

  private[this] final val initialBlockSize =
    2 // TODO: maybe start with bigger for platform threads?

  private[this] val ctr =
    new AtomicLong(java.lang.Long.MIN_VALUE) // TODO: VarHandle, padding

  private[GlobalRefIdGen] final def allocateThreadLocalBlock(size: Int): Long = {
    require(size > 0)
    val s = size.toLong
    val n = this.ctr.getAndAdd(s) // TODO: opaque
    assert(n < (n + s)) // ID overflow
    n
  }

  final def newThreadLocal(): RefIdGen = {
    new GlobalRefIdGen.ThreadLocalRefIdGen(
      parent = this,
      next = 0L, // unused, because:
      remaining = 0, // initially no more remaining
      nextBlockSize = initialBlockSize,
    )
  }

  @inline
  final override def nextId(): Long =
    this.nextIdGlobal()

  @inline
  final override def nextArrayIdBase(size: Int): Long =
    this.nextArrayIdBaseGlobal(size)

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

  /** Returns idBase for RefArrays */ // TODO: is ID overflow plausible with big arrays?
  final def nextArrayIdBaseGlobal(size: Int): Long = {
    this.allocateThreadLocalBlock(size)
  }
}

private[mcas] object GlobalRefIdGen {

  final class ThreadLocalRefIdGen private[GlobalRefIdGen] (
    private[this] val parent: GlobalRefIdGen,
    private[this] var next: Long,
    private[this] var remaining: Int,
    private[this] var nextBlockSize: Int,
  ) extends RefIdGen {

    private[this] final val maxBlockSize =
      1 << 30

    @tailrec
    final override def nextId(): Long = {
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
        // now we'll succeed for sure:
        this.nextId()
      }
    }

    @tailrec
    final override def nextArrayIdBase(size: Int): Long = {
      require(size > 0)
      val rem = this.remaining
      if (rem >= size) {
        // It fits into the current block:
        val base = this.next
        this.next = base + size.toLong
        this.remaining = rem - size
        base
      } else if (size <= maxBlockSize) {
        // It doesn't fit into the current block, but
        // it can fit into a new block. We'll leak the
        // remaining IDs in the current block. We'll
        // potentially also leak less than half of the
        // new block (if the thread-local is abandoned
        // right after this). Overall, we leak less
        // than `2*size`, which is worse than our "usual"
        // amount (which is `<= used`; in `nextId`).
        // This means that (worst case) we can still use
        // (a little more than) one-third of the whole space.
        this.allocateBlockForArray(size)
        // now we'll succeed for sure:
        this.nextArrayIdBase(size)
      } else {
        // Exceptionally large array, so we just fulfill
        // this request directly from the global. (There
        // is zero leak here.)
        this.parent.nextArrayIdBaseGlobal(size)
      }
    }

    private[this] final def allocateBlockForArray(arraySize: Int): Unit = {
      val s = RefIdGenBase.nextPowerOf2(arraySize)
      this.next = this.parent.allocateThreadLocalBlock(s)
      this.remaining = s
      val nbs = this.nextBlockSize
      if ((s > nbs) && (s < maxBlockSize)) {
        this.nextBlockSize = s << 1
      }
    }
  }
}
