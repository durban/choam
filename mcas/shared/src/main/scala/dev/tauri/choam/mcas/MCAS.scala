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

  final object INVALID {
    def of[A]: A =
      this.asInstanceOf[A]
  }

  final def isInvalid[A](a: A): Boolean =
    equ(a, INVALID.of[A])

  trait ThreadContext {

    // abstract:

    // TODO: should be protected[mcas]
    def readCommitTs(): Long

    // TODO: remove this
    protected[mcas] def setCommitTs(v: Long): Unit

    // TODO: do we need this? (for read-only Rxn's?)
    /** Returns `INVALID` if version is newer than `validTs` */
    def readIfValid[A](ref: MemoryLocation[A], validTs: Long): A

    def readIntoHwd[A](ref: MemoryLocation[A], validTs: Long): HalfWordDescriptor[A] =
      null // TODO: this should be abstract

    def readVersion[A](ref: MemoryLocation[A]): Long

    def tryPerform(desc: HalfEMCASDescriptor): Boolean

    private[choam] def random: ThreadLocalRandom

    // concrete:

    final def start(): HalfEMCASDescriptor =
      HalfEMCASDescriptor.empty(ts = this.readCommitTs())

    final def read[A](ref: MemoryLocation[A]): A =
      this.readIfValid(ref, Version.Invalid)

    final def addCas[A](desc: HalfEMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A): HalfEMCASDescriptor =
      // TODO: this Version.Invalid is going to be a problem!
      this.addCasWithVersion(desc, ref, ov = ov, nv = nv, version = Version.Invalid)

    final def addCasWithVersion[A](
      desc: HalfEMCASDescriptor,
      ref: MemoryLocation[A],
      ov: A,
      nv: A,
      version: Long
    ): HalfEMCASDescriptor = {
      val wd = HalfWordDescriptor(ref, ov, nv, version)
      desc.add(wd)
    }

    final def validate(desc: HalfEMCASDescriptor): Boolean = {
      @tailrec
      def go(it: Iterator[HalfWordDescriptor[_]]): Boolean = {
        if (it.hasNext) {
          val wd = it.next()
          val currVer: Long = this.readVersion(wd.address)
          if (currVer == wd.version) {
            go(it)
          } else {
            false
          }
        } else {
          true
        }
      }

      go(desc.map.valuesIterator)
    }

    final def snapshot(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      desc

    final def addAll(to: HalfEMCASDescriptor, from: HalfEMCASDescriptor): HalfEMCASDescriptor = {
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

    // statistics/testing/benchmarking:

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
