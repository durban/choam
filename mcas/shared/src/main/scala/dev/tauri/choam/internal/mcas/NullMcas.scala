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

import java.util.concurrent.ThreadLocalRandom

/**
 * This is a very limited MCAS implementation,
 * which can only perform *empty* MCAS operations
 * (i.e., not even reading is implemented). All
 * other operations throw an `UnsupportedOperationException`.
 *
 * It can be useful in special cases (for low-level optimization),
 * when it is necessary to immediately execute a `Rxn` which
 * is known not to actually do any k-CASes.
 */
object NullMcas extends Mcas.UnsealedMcas { self =>

  private[this] val ctx =
    new NullContext

  private[this] val globalVersion =
    MemoryLocation.unsafeUnpadded[Long](Version.Start)

  final override def currentContext(): Mcas.ThreadContext =
    this.ctx

  private[choam] final override def isThreadSafe: Boolean =
    true

  private[this] final class NullContext extends Mcas.UnsealedThreadContext {

    final override type START = Descriptor

    final override def impl: Mcas =
      self

    final override def start(): Descriptor =
      Descriptor.empty(globalVersion, this)

    private[mcas] final override def addVersionCas(desc: AbstractDescriptor): AbstractDescriptor.Aux[desc.D] =
      throw new UnsupportedOperationException

    final override def readDirect[A](ref: MemoryLocation[A]): A = {
      if (ref eq globalVersion) {
        val res = ref.unsafeGetV()
        _assert(res.asInstanceOf[java.lang.Long].longValue() == Version.Start)
        res
      } else {
        throw new UnsupportedOperationException
      }
    }

    final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] =
      throw new UnsupportedOperationException

    private[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      throw new UnsupportedOperationException

    final override def validateAndTryExtend(
      desc: AbstractDescriptor,
      hwd: LogEntry[_]
    ): AbstractDescriptor.Aux[desc.D] = {
      throw new UnsupportedOperationException
    }

    private[mcas] final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long = {
      if (desc.nonEmpty) {
        throw new UnsupportedOperationException
      } else {
        McasStatus.Successful
      }
    }

    final override def random: ThreadLocalRandom =
      ThreadLocalRandom.current()

    final override def refIdGen: RefIdGen =
      RefIdGen.global
  }
}
