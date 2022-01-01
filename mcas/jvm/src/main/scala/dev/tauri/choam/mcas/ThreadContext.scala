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
package mcas

import java.util.concurrent.ThreadLocalRandom

// TODO: rename to EMCASThreadContext
private final class ThreadContext(
  global: GlobalContext,
  private[mcas] val tid: Long,
  val impl: EMCAS.type
) extends IBR.ThreadContext[ThreadContext](global, 0)
  with MCAS.ThreadContext {

  private[this] var finalizedDescriptors: EMCASDescriptor =
    null

  private[this] var finalizedDescriptorsCount: Int =
    0

  private[this] var maxFinalizedDescriptorsCount: Int =
    0

  private[this] var commits: Int =
    0

  private[this] var retries: Int =
    0

  // TODO: this is a hack
  private[this] var statistics: Map[AnyRef, AnyRef] =
    Map.empty

  // NB: it is a `val`, not a `def`
  private[choam] final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override def tryPerform(desc: HalfEMCASDescriptor): Boolean =
    impl.tryPerform(desc, this)

  final override def read[A](loc: MemoryLocation[A]): A =
    impl.read(loc, this)

  final override def toString: String = {
    s"ThreadContext(global = ${this.global}, tid = ${this.tid})"
  }

  /**
   * The descriptor `desc` was finalized (i.e., succeeded or failed). Put it
   * in the list of finalized descriptors, and run GC cleanup (IBR) with a small
   * probability.
   *
   * @param desc The descriptor which was finalized.
   * @param limit Don't run the GC if the number of finalized decriptors is less than `limit`.
   * @param replace The period with which to run GC (IBR); should be a power of 2; `replace = N`
   *                makes the GC run with a probability of `1 / N`.
   */
  final def finalized(desc: EMCASDescriptor, limit: Int, replace: Int): Unit = {
    if (this.finalizedDescriptorsCount == Int.MaxValue) {
      throw new java.lang.ArithmeticException("finalizedDescriptorsCount overflow")
    }
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

  private[choam] final def getFinalizedDescriptorsCount(): Int =
    this.finalizedDescriptorsCount

  private[choam] final def getMaxFinalizedDescriptorsCount(): Int =
    this.maxFinalizedDescriptorsCount

  private final def runCleanup(giveUpAt: Long = 256L): Unit = {
    @tailrec
    def replace(words: java.util.Iterator[WordDescriptor[_]], accDone: Boolean): Boolean = {
      if (words.hasNext()) {
        val done = words.next() match {
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
              words.remove()
              true
            } else {
              // TODO: 'plain' might not be enough
              if (Math.abs(this.global.getEpoch() - wd.getMaxEpochPlain()) >= giveUpAt) {
                // We couldn't replace this `WordDescriptor` for
                // a long time now, so we just give up. We'll
                // release the reference; it might be cleared up
                // on a subsequent `EMCAS.readValue`, in which case
                // the JVM GC will be able to collect it.
                words.remove()
                true
              } else {
                // We'll try next time
                false
              }
            }
        }
        replace(words, if (done) accDone else false)
      } else {
        accDone
      }
    }
    @tailrec
    def go(curr: EMCASDescriptor, prev: EMCASDescriptor): Unit = {
      if (curr ne null) {
        val done = replace(curr.wordIterator(), true)
        val newPrev = if (done) {
          // delete the descriptor from the list:
          assert(this.finalizedDescriptorsCount >= 1) // TODO: remove
          this.finalizedDescriptorsCount -= 1
          if (prev ne null) {
            // delete an internal item:
            prev.next = curr.next
          } else {
            // delete the head:
            assert(this.finalizedDescriptors eq curr) // TODO: remove
            this.finalizedDescriptors = curr.next
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

  /** Only for testing/benchmarking */
  private[choam] final override def recordCommit(retries: Int): Unit = {
    this.commits += 1
    this.retries += retries
  }

  /** Only for testing/benchmarking */
  private[choam] def getCommitsAndRetries(): (Int, Int) = {
    (this.commits, this.retries)
  }

  /** Only for testing/benchmarking */
  private[choam] final override def supportsStatistics: Boolean = {
    true
  }

  /** Only for testing/benchmarking */
  private[choam] final override def getStatistics(): Map[AnyRef, AnyRef] = {
    this.statistics
  }

  /** Only for testing/benchmarking */
  private[choam] final override def setStatistics(stats: Map[AnyRef, AnyRef]): Unit = {
    this.statistics = stats
  }
}
