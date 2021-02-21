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
package kcas

import java.util.concurrent.ThreadLocalRandom

/**
 * Efficient Multi-word Compare and Swap:
 * https://arxiv.org/pdf/2008.02527.pdf
 */
private[kcas] object EMCAS extends KCAS { self =>

  private[kcas] val global =
    new GlobalContext(self)

  // Listing 2 in the paper:

  /**
   * A specialized version of `readInternal` from the paper
   *
   * Only returns the actual value (after possibly helping).
   * Cannot be called from an ongoing MCAS operation (but
   * can be called when we're only reading).
   *
   * (The other version of `readInternal`, specialized for
   * an ongoing MCAS operation is inlined into `tryWord` below,
   * see the `while` loop.)
   */
  private[choam] final def readValue[A](ref: Ref[A], ctx: ThreadContext, replace: Int = 256): A = {
    @tailrec
    def go(): A = {
      ctx.readVolatileRef[A](ref) match {
        case wd: WordDescriptor[_] =>
          val parentStatus = wd.parent.getStatus()
          if (parentStatus eq EMCASStatus.ACTIVE) {
            MCAS(wd.parent, helping = true, ctx = ctx, replace = replace) // help the other op
            go() // retry
          } else { // finalized
            val a = if (parentStatus eq EMCASStatus.SUCCESSFUL) {
              wd.cast[A].nv
            } else { // FAILED
              wd.cast[A].ov
            }
            this.maybeReplaceDescriptor[A](ref, wd.cast[A], a, ctx, replace = replace)
            a
          }
        case a =>
          a
      }
    }

    ctx.startOp()
    try go()
    finally ctx.endOp()
  }

  private final def maybeReplaceDescriptor[A](ref: Ref[A], ov: WordDescriptor[A], nv: A, ctx: ThreadContext, replace: Int): Unit = {
    if (replace != 0) {
      val n = ThreadLocalRandom.current().nextInt()
      if ((n % replace) == 0) {
        replaceDescriptorIfFree[A](ref, ov, nv, ctx)
        ()
      }
    }
  }

  private[kcas] final def replaceDescriptorIfFree[A](ref: Ref[A], ov: WordDescriptor[A], nv: A, ctx: ThreadContext): Boolean = {
    if ((!ctx.isInUseByOther(ov.cast[Any]))) {
      ref.unsafeTryPerformCas(ov.castToData, nv)
      // If this CAS fails, someone else might've been
      // replaced the desc with the final value, or
      // maybe started another operation; in either case,
      // there is nothing to do, so we always indicate success
      true
    } else {
      // still in use, need to be replaced later
      false
    }
  }

  // Listing 3 in the paper:

  private[choam] final override def read[A](ref: Ref[A], ctx: ThreadContext): A =
    readValue(ref, ctx)

  /**
   * Performs an MCAS operation.
   *
   * @param desc: The main descriptor.
   * @param helping: Pass `true` when helping `desc` found in a `Ref`;
   *                 `false` when `desc` is a new descriptor.
   */
  def MCAS(desc: EMCASDescriptor, helping: Boolean, ctx: ThreadContext, replace: Int = 4096): Boolean = {
    @tailrec
    def tryWord[A](wordDesc: WordDescriptor[A]): TryWordResult = {
      var content: A = nullOf[A]
      var contentWd: WordDescriptor[A] = null
      var value: A = nullOf[A]
      var go = true
      // Read `content`, and `value` if necessary;
      // this is a specialized and inlined version
      // of `readInternal` from the paper. We're
      // using a `while` loop instead of a tail-recursive
      // function (like in the paper), because we may
      // need both `content` and `value`, and returning
      // them would require allocating a tuple (like in
      // the paper).
      while (go) {
        contentWd = null
        content = ctx.readVolatileRef(wordDesc.address)
        content match {
          case wd: WordDescriptor[_] =>
            if (wd eq wordDesc) {
              // already points to the right place, early return:
              return TryWordResult.SUCCESS // scalastyle:ignore return
            } else {
              contentWd = wd.cast[A]
              // At this point, we're sure that `wd` belongs to another op
              // (not `desc`), because otherwise it would've been equal to
              // `wordDesc` (we're assuming that any WordDescriptor only
              // appears at most once in an MCASDescriptor).
              val parentStatus = wd.parent.getStatus()
              if (parentStatus eq EMCASStatus.ACTIVE) {
                MCAS(wd.parent, helping = true, ctx = ctx, replace = replace) // help the other op
                // Note: we're not "helping" ourselves for sure, see the comment above.
                // Here, we still don't have the value, so the loop must retry.
              } else if (parentStatus eq EMCASStatus.SUCCESSFUL) {
                value = wd.cast[A].nv
                go = false
              } else {
                value = wd.cast[A].ov
                go = false
              }
            }
          case a =>
            value = a
            go = false
        }
      }

      if (!equ(value, wordDesc.ov)) {
        // expected value is different
        TryWordResult.FAILURE
      } else if (desc.getStatus() ne EMCASStatus.ACTIVE) {
        // we have been finalized (by a helping thread), no reason to continue
        TryWordResult.BREAK
      } else {
        if (contentWd ne null) {
          extendInterval(oldWd = contentWd, newWd = wordDesc)
        }
        if (!ctx.casRef(wordDesc.address, content, wordDesc.castToData)) {
          // CAS failed, we'll retry. This means, that we maybe
          // unnecessarily extended the interval; but that is
          // not a correctness problem (just makes IBR less precise).
          tryWord(wordDesc)
        } else {
          TryWordResult.SUCCESS
        }
      }
    }

    /**
     * Extends the interval of `newWd` to include the interval of `oldWd`.
     *
     * Another thread could simultaneously try to do the same, so we have to take
     * care to increase/decrease values atomically. If more than one thread performs
     * the increase/decrease, the worst that could happen is to have an unnecessarily
     * large interval. That would make IBR less effective, but still correct.
     *
     * Assumption: we only ever extend intervals (never narrow them).
     *
     * Also note, that after a descriptor is installed into a `Ref`, its
     * min/max epochs only change if another thread is still executing
     * this method. However, in that case, the CAS after this method will
     * fail for sure (since it was already installed). Thus, we don't need
     * to re-check after extending the interval.
     */
    def extendInterval[A](oldWd: WordDescriptor[A], newWd: WordDescriptor[A]): Unit = {
      val newMin = newWd.getMinEpochAcquire()
      val oldMin = oldWd.getMinEpochAcquire()
      if (oldMin < newMin) {
        // FAA atomically decreases the value:
        newWd.decreaseMinEpochRelease(newMin - oldMin)
      }
      val newMax = newWd.getMaxEpochAcquire()
      val oldMax = oldWd.getMaxEpochAcquire()
      if (oldMax > newMax) {
        // FAA atomically increases the value:
        newWd.increaseMaxEpochRelease(oldMax - newMax)
      }
    }

    @tailrec
    def go(words: java.util.Iterator[WordDescriptor[_]]): TryWordResult = {
      if (words.hasNext) {
        val twr = tryWord(words.next())
        if (twr eq TryWordResult.SUCCESS) go(words)
        else twr
      } else {
        TryWordResult.SUCCESS
      }
    }

    if (!helping) {
      // we're not helping, so `desc` is not yet visible to other threads
      // (otherwise the thread which published `desc` already sorted it)
      desc.sort()
      ctx.startOp()
    }

    try {
      val r = go(desc.words.iterator())
      if (r eq TryWordResult.BREAK) {
        // someone else finalized the descriptor, we must read its status:
        (desc.getStatus() eq EMCASStatus.SUCCESSFUL)
      } else {
        val rr = if (r eq TryWordResult.SUCCESS) {
          EMCASStatus.SUCCESSFUL
        } else {
          EMCASStatus.FAILED
        }
        if (desc.casStatus(EMCASStatus.ACTIVE, rr)) {
          // TODO:
          // ctx.finalized(desc, limit = replace)
          (rr eq EMCASStatus.SUCCESSFUL)
        } else {
          // someone else finalized the descriptor, we must read its status:
          (desc.getStatus() eq EMCASStatus.SUCCESSFUL)
        }
      }
    } finally {
      if (!helping) {
        ctx.endOp()
      }
    }
  }

  private[choam] final override def currentContext(): ThreadContext =
    this.global.threadContext()

  private[choam] final override def start(ctx: ThreadContext): EMCASDescriptor =
    new EMCASDescriptor()

  private[choam] final override def addCas[A](
    desc: EMCASDescriptor,
    ref: Ref[A],
    ov: A,
    nv: A,
    ctx: ThreadContext
  ): EMCASDescriptor = {
    ctx.startOp()
    try {
      val wd = WordDescriptor[A](ref, ov, nv, desc, ctx)
      desc.words.add(wd)
      desc
    } finally ctx.endOp()
  }

  private[choam] final override def snapshot(desc: EMCASDescriptor, ctx: ThreadContext): EMCASDescriptor = {
    desc.copy(ctx)
  }

  private[choam] final override def tryPerform(desc: EMCASDescriptor, ctx: ThreadContext): Boolean = {
    EMCAS.MCAS(desc, helping = false, ctx = ctx)
  }

  /** For testing */
  @throws[InterruptedException]
  private[kcas] def spinUntilCleanup[A](ref: Ref[A], max: Long = Long.MaxValue): A = {
    val ctx = this.currentContext()
    var ctr: Long = 0L
    while (ctr < max) {
      ref.unsafeTryRead() match {
        case wd: WordDescriptor[_] =>
          if (wd.parent.getStatus() eq EMCASStatus.ACTIVE) {
            // CAS in progress, retry
          } else {
            // CAS finalized, but no cleanup yet, read and retry
            EMCAS.read(ref, ctx)
            ctx.forceNextEpoch() // TODO: in a real program, when does this happen?
          }
        case a =>
          // descriptor have been cleaned up:
          return a // scalastyle:ignore return
      }
      Thread.onSpinWait()
      ctr += 1L
      if ((ctr % 128L) == 0L) {
        if (Thread.interrupted()) {
          throw new InterruptedException
        }
      }
    }
    nullOf[A]
  }
}
