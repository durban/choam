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

import java.util.concurrent.ThreadLocalRandom

import scala.collection.concurrent.TrieMap

object FlakyEMCAS extends Mcas.UnsealedMcas { self =>

  private[this] val seen =
    new TrieMap[Int, Unit]

  def currentContext(): Mcas.ThreadContext = new Mcas.UnsealedThreadContext {

    private[this] val emcasCtx =
      Emcas.inst.currentContextInternal()

    final override def impl: Mcas =
      self

    final override def random: ThreadLocalRandom =
      emcasCtx.random

    final override def refIdGen =
      emcasCtx.refIdGen

    final override def readDirect[A](ref: MemoryLocation[A]): A =
      emcasCtx.readDirect(ref)

    final override def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] =
      emcasCtx.readIntoHwd(ref)

    protected[mcas] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      emcasCtx.readVersion(ref)

    final override def tryPerformInternal(desc: Descriptor): Long =
      self.tryPerformInternal(desc, emcasCtx)

    final override def start(): Descriptor =
      emcasCtx.start()

    protected[mcas] final override def addVersionCas(desc: Descriptor): Descriptor =
      emcasCtx.addVersionCas(desc)

    final override def validateAndTryExtend(
      desc: Descriptor,
      hwd: HalfWordDescriptor[_],
    ): Descriptor = {
      emcasCtx.validateAndTryExtend(desc, hwd)
    }
  }

  private[choam] final override def isThreadSafe =
    true

  private final def tryPerformInternal(hDesc: Descriptor, ctx: EmcasThreadContext): Long = {
    // perform or not the operation based on whether we've already seen it
    val desc = EmcasDescriptor.prepare(hDesc)
    var hash = 0x75F4D07D
    val it = desc.wordIterator()
    while (it.hasNext()) {
      hash ^= it.next().address.##
    }
    if (this.seen.putIfAbsent(hash, ()).isDefined) {
      val r = Emcas.inst.MCAS(desc = desc, ctx = ctx)
      if (EmcasStatus.isSuccessful(r)) McasStatus.Successful
      else r
    } else {
      McasStatus.FailedVal // simulate a transient CAS failure to force a retry
    }
  }
}
