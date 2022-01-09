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

/** Common interface for MCAS (i.e., k-CAS) implementations */
abstract class MCAS {

  /** Returns the context associated with the current thread */
  def currentContext(): MCAS.ThreadContext

  /** True iff `this` can be used to perform ops on arbitrary refs */
  private[choam] def isThreadSafe: Boolean

  /** Only for testing/benchmarking */
  private[choam] def printStatistics(@unused println: String => Unit): Unit = {
    ()
  }

  /** Only for testing/benchmarking */
  private[choam] def countCommitsAndRetries(): (Long, Long) = {
    (0L, 0L)
  }

  /** Only for testing/benchmarking */
  private[choam] def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    Map.empty
  }

  /** Only for testing/benchmarking */
  private[choam] def maxReusedWeakRefs(): Int = {
    0
  }
}

object MCAS extends MCASPlatform { self =>

  trait ThreadContext {

    def tryPerform(desc: HalfEMCASDescriptor): Boolean

    def read[A](ref: MemoryLocation[A]): A

    def start(): HalfEMCASDescriptor =
      HalfEMCASDescriptor.empty

    def readCommitTs(): Long

    def addCas[A](desc: HalfEMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A): HalfEMCASDescriptor =  {
      val wd = HalfWordDescriptor(ref, ov, nv)
      desc.add(wd)
    }

    def snapshot(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      desc

    def addAll(to: HalfEMCASDescriptor, from: HalfEMCASDescriptor): HalfEMCASDescriptor = {
      val it = from.map.valuesIterator
      var res = to
      while (it.hasNext) {
        res = res.add(it.next())
      }
      res
    }

    final def doSingleCas[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      val desc = this.addCas(this.start(), ref, ov, nv)
      this.tryPerform(desc)
    }

    private[choam] def random: ThreadLocalRandom

    /** Only for testing/benchmarking */
    private[choam] def recordCommit(@unused retries: Int): Unit = {
      // we ignore stats by default; implementations
      // can override if it matters
      ()
    }

    /** Only for testing/benchmarking */
    private[choam] def supportsStatistics: Boolean = {
      // we ignore stats by default; implementations
      // can override if it matters
      false
    }

    /** Only for testing/benchmarking */
    private[choam] def getStatisticsPlain(): Map[AnyRef, AnyRef] = {
      // we ignore stats by default; implementations
      // can override if it matters
      Map.empty
    }

    /** Only for testing/benchmarking */
    private[choam] def getStatisticsOpaque(): Map[AnyRef, AnyRef] = {
      // we ignore stats by default; implementations
      // can override if it matters
      Map.empty
    }

    /** Only for testing/benchmarking */
    private[choam] def setStatisticsPlain(@unused stats: Map[AnyRef, AnyRef]): Unit = {
      // we ignore stats by default; implementations
      // can override if it matters
    }

    /** Only for testing/benchmarking */
    private[choam] def maxReusedWeakRefs(): Int = {
      0
    }
  }

  private[mcas] def impossibleOp[A, B](
    ref: MemoryLocation[_],
    a: HalfWordDescriptor[A],
    b: HalfWordDescriptor[B]
  ): Nothing = {
    throw new ImpossibleOperation(ref, a, b)
  }
}
