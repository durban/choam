/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

object NullMcas extends MCAS {

  private[this] val ctx =
    new NullContext

  private[this] val globalVersion =
    MemoryLocation.unsafeUnpadded[Long](Version.Start)

  final override def currentContext(): MCAS.ThreadContext =
    this.ctx

  private[choam] final override def isThreadSafe: Boolean =
    true

  private[this] final class NullContext extends MCAS.ThreadContext {

    final override def start(): HalfEMCASDescriptor =
      HalfEMCASDescriptor.empty(globalVersion, this)

    private[mcas] final override def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      throw new UnsupportedOperationException

    final override def readDirect[A](ref: MemoryLocation[A]): A = {
      if (ref eq globalVersion) {
        val res = ref.unsafeGetVolatile()
        assert(res.asInstanceOf[java.lang.Long].longValue() == Version.Start)
        res
      } else {
        throw new UnsupportedOperationException
      }
    }

    final override def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] =
      throw new UnsupportedOperationException

    private[mcas] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      throw new UnsupportedOperationException

    private[choam] final override def validateAndTryExtend(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      throw new UnsupportedOperationException

    private[mcas] final override def tryPerformInternal(desc: HalfEMCASDescriptor): Long = {
      if (desc.nonEmpty) {
        throw new UnsupportedOperationException
      } else {
        EmcasStatus.Successful
      }
    }

    private[choam] final override def random: ThreadLocalRandom =
      ThreadLocalRandom.current()
  }
}
