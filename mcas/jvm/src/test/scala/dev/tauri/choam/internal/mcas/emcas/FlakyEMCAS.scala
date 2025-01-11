/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

  private[this] val emcasInst: Emcas =
    new Emcas(BaseSpec.osRngForTesting)

  private[this] val seen =
    new TrieMap[Int, Unit]

  private[choam] final override val osRng: OsRng =
    BaseSpec.osRngForTesting

  def currentContext(): Mcas.ThreadContext = new Mcas.UnsealedThreadContext {

    final override type START = MutDescriptor

    private[this] val emcasCtx =
      emcasInst.currentContextInternal()

    final override def impl: Mcas =
      self

    final override def random: ThreadLocalRandom =
      emcasCtx.random

    final override def refIdGen =
      emcasCtx.refIdGen

    final override def readDirect[A](ref: MemoryLocation[A]): A =
      emcasCtx.readDirect(ref)

    final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] =
      emcasCtx.readIntoHwd(ref)

    protected[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      emcasCtx.readVersion(ref)

    final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long =
      self.tryPerformInternal(desc, emcasCtx, optimism)

    final override def start(): MutDescriptor =
      emcasCtx.start()

    protected[mcas] final override def addVersionCas(desc: AbstractDescriptor): AbstractDescriptor.Aux[desc.D] =
      emcasCtx.addVersionCas(desc)

    final override def validateAndTryExtend(
      desc: AbstractDescriptor,
      hwd: LogEntry[_],
    ): AbstractDescriptor.Aux[desc.D] = {
      emcasCtx.validateAndTryExtend(desc, hwd)
    }
  }

  private[choam] final override def isThreadSafe =
    true

  private final def tryPerformInternal(hDesc: AbstractDescriptor, ctx: EmcasThreadContext, optimism: Long): Long = {
    // perform or not the operation based on whether we've already seen it
    if (this.seen.putIfAbsent(hDesc.##, ()).isDefined) {
      emcasInst.tryPerformDebug(desc = hDesc, ctx = ctx, optimism = optimism)
    } else {
      McasStatus.FailedVal // simulate a transient CAS failure to force a retry
    }
  }
}
