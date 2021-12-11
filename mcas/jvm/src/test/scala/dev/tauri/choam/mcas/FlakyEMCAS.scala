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

import scala.collection.concurrent.TrieMap

import mcas.MemoryLocation

object FlakyEMCAS extends MCAS { self =>

  private[this] val seen =
    new TrieMap[Int, Unit]

  def currentContext(): MCAS.ThreadContext = new MCAS.ThreadContext {

    private[this] val emcasCtx =
      EMCAS.currentContext()

    private[choam] final override def random: ThreadLocalRandom =
      emcasCtx.random

    final override def read[A](ref: MemoryLocation[A]): A =
      emcasCtx.read(ref)

    final override def tryPerform(desc: HalfEMCASDescriptor): Boolean =
      self.tryPerform(desc, emcasCtx)
  }

  private[choam] final override def isAtomic =
    true

  private final def tryPerform(hDesc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean = {
    // perform or not the operation based on whether we've already seen it
    ctx.startOp()
    try {
      val desc = EMCASDescriptor.prepare(hDesc, ctx)
      var hash = 0x75F4D07D
      val it = desc.wordIterator()
      while (it.hasNext()) {
        hash ^= it.next().address.##
      }
      if (this.seen.putIfAbsent(hash, ()).isDefined) {
        EMCAS.MCAS(desc = desc, ctx = ctx, replace = EMCAS.replacePeriodForEMCAS)
      } else {
        false // simulate a transient CAS failure to force a retry
      }
    } finally {
      ctx.endOp()
    }
  }
}
