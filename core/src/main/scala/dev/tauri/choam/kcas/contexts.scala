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

final class GlobalContext(impl: KCAS)
  extends IBR[ThreadContext](Long.MinValue) {

  override def newThreadContext(): ThreadContext =
    new ThreadContext(this, Thread.currentThread().getId(), impl)
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

  val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override def toString: String = {
    s"ThreadContext(global = ${this.global}, tid = ${this.tid})"
  }

  private[choam] var maxBackoff: Int =
    16

  private[choam] var randomizeBackoff: Boolean =
    true

  final def finalized(desc: EMCASDescriptor, limit: Int = 256, replace: Int = 256): Unit = {
    desc.next = this.finalizedDescriptors
    this.finalizedDescriptors = desc
    this.finalizedDescriptorsCount += 1
    if ((this.finalizedDescriptorsCount > limit) && ((this.random.nextInt() % replace) == 0)) {
      this.runCleanup()
    }
  }

  private final def runCleanup(giveUpAt: Long = 256): Unit = {
    @tailrec
    def replace(iter: java.util.Iterator[WordDescriptor[_]], acc: Boolean): Boolean = {
      if (iter.hasNext) {
        val done = iter.next() match { case wd: WordDescriptor[a] =>
          val nv = if (wd.parent.getStatus() eq EMCASStatus.SUCCESSFUL) {
            wd.nv
          } else {
            wd.ov
          }
          if (!EMCAS.replaceDescriptorIfFree(wd.address, wd, nv, this)) {
            // TODO: 'plain' might not be enough
            if (Math.abs(this.global.getEpoch() - wd.getMaxEpochPlain()) >= giveUpAt) {
              true
            } else {
              false
            }
          } else {
            true
          }
        }
        replace(iter, if (done) acc else false)
      } else {
        acc
      }
    }
    @tailrec
    def go(curr: EMCASDescriptor, prev: EMCASDescriptor): Unit = {
      if (curr ne null) {
        val done = replace(curr.words.iterator(), true)
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
