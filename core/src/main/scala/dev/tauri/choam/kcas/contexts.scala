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

import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom

final class GlobalContext(impl: KCAS)
  extends IBR[ThreadContext](Long.MinValue) {

  override def newThreadContext(): ThreadContext =
    new ThreadContext(this, Thread.currentThread().getId(), impl)

  /** Only for testing/benchmarking */
  private[choam] def countFinalizedDescriptors(): (Long, Long) = {
    val tctxs = this.snapshotReservations
    var countDescrs = 0L
    var countMaxDescrs = 0L
    tctxs.valuesIterator.foreach { weakref =>
      weakref.get() match {
        case null =>
          ()
        case tctx =>
          // Calling `getFinalizedDescriptorsCount` is not
          // thread-safe here, but we only need these statistics
          // for benchmarking, so we're just hoping for the best...
          countDescrs += tctx.getFinalizedDescriptorsCount().toLong
          countMaxDescrs += tctx.getMaxFinalizedDescriptorsCount().toLong
      }
    }

    (countDescrs, countMaxDescrs)
  }
}

final class ThreadContext(
  global: GlobalContext,
  private[kcas] val tid: Long,
  val impl: KCAS
) extends IBR.ThreadContext[ThreadContext](global, 0) {

  private[this] var finalizedDescriptors: EMCASDescriptor =
    null

  private[this] var finalizedDescriptorsCount: Int =
    0

  private[this] var maxFinalizedDescriptorsCount: Int =
    0

  val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override def toString: String = {
    s"ThreadContext(global = ${this.global}, tid = ${this.tid})"
  }

  // TODO: put this in `ReactionData`; make it a `val`
  private[choam] var maxBackoff: Int =
    16

  // TODO: put this in `ReactionData`; make it a `val`
  private[choam] var randomizeBackoff: Boolean =
    true

  // TODO: do we need this?
  private[choam] var onRetry: ArrayList[Axn[Unit]] =
    new ArrayList

  /**
   * The descriptor `desc` was finalized (i.e., succeeded or failed). Put it
   * in the list of finalized descriptors, and run GC cleanup (IBR) with a small
   * probability.
   *
   * @param desc The descriptor whichh was finalized.
   * @param limit Don't run the GC if the number of finalized decriptors is less than `limit`.
   * @param replace The period with which to run GC (IBR); should be a power of 2; `replace = N`
   *                makes the GC run with a probability of `1 / N`.
   */
  final def finalized(desc: EMCASDescriptor, limit: Int, replace: Int): Unit = {
    desc.next = this.finalizedDescriptors
    this.finalizedDescriptors = desc
    this.finalizedDescriptorsCount += 1
    if (this.finalizedDescriptorsCount > this.maxFinalizedDescriptorsCount) {
      this.maxFinalizedDescriptorsCount = this.finalizedDescriptorsCount
    }
    if ((this.finalizedDescriptorsCount > limit) && ((this.random.nextInt() % replace) == 0)) {
      this.runCleanup()
    }
  }

  private[kcas] final def getFinalizedDescriptorsCount(): Int =
    this.finalizedDescriptorsCount

  private[kcas] final def getMaxFinalizedDescriptorsCount(): Int =
    this.maxFinalizedDescriptorsCount

  private final def runCleanup(giveUpAt: Long = 256): Unit = {
    @tailrec
    def replace(idx: Int, words: ArrayList[WordDescriptor[_]], accDone: Boolean): Boolean = {
      if (idx < words.size) {
        val done = words.get(idx) match {
          case null =>
            // already replaced and cleared
            true
          case wd: WordDescriptor[a] =>
            val nv = if (wd.parent.getStatus() eq EMCASStatus.SUCCESSFUL) {
              wd.nv
            } else {
              wd.ov
            }
            if (EMCAS.replaceDescriptorIfFree(wd.address, wd, nv, this)) {
              // OK, this `WordDescriptor` have been replaced, we can clear it:
              words.set(idx, null)
              true
            } else {
              // TODO: 'plain' might not be enough
              if (Math.abs(this.global.getEpoch() - wd.getMaxEpochPlain()) >= giveUpAt) {
                // We couldn't replace this `WordDescriptor` for
                // a long time now, so we just give up. We'll
                // release the reference; it might be cleared up
                // on a subsequent `EMCAS.readValue`, in which case
                // the JVM GC will be able to collect it.
                words.set(idx, null)
                true
              } else {
                // We'll try next time
                false
              }
            }
        }
        replace(idx + 1, words, if (done) accDone else false)
      } else {
        accDone
      }
    }
    @tailrec
    def go(curr: EMCASDescriptor, prev: EMCASDescriptor): Unit = {
      if (curr ne null) {
        val done = replace(0, curr.words, true)
        val newPrev = if (done) {
          // delete the descriptor from the list:
          this.finalizedDescriptorsCount -= 1
          if (prev ne null) {
            // delete an internal item:
            prev.next = curr.next
          } else {
            // delete the head:
            this.finalizedDescriptors.next = curr.next
          }
          prev
        } else {
          curr
        }
        go(curr.next, prev = newPrev)
      }
    }
    go(this.finalizedDescriptors, prev = null)
  }
}
