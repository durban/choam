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

    def start(): HalfEMCASDescriptor

    /** Only for testing! */
    protected[mcas] def setCommitTs(v: Long): Unit

    protected[mcas] def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor

    // TODO: do we need this? (for read-only Rxn's?)
    /** Returns `INVALID` if version is newer than `validTs` */
    def readIfValid[A](ref: MemoryLocation[A], validTs: Long): A

    def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A]

    protected[mcas] def readVersion[A](ref: MemoryLocation[A]): Long

    protected[mcas] def validateAndTryExtend(desc: HalfEMCASDescriptor): HalfEMCASDescriptor

    def tryPerform(desc: HalfEMCASDescriptor): Boolean

    private[choam] def random: ThreadLocalRandom

    // concrete:

    // TODO: remove (use readDirect instead)
    final def read[A](ref: MemoryLocation[A]): A =
      this.readIfValid(ref, Version.None)

    final def readDirect[A](ref: MemoryLocation[A]): A =
      this.readIfValid(ref, Version.None)

    // TODO: there should be a non-option, non-tuple version of this
    final def readMaybeFromLog[A](ref: MemoryLocation[A], log: HalfEMCASDescriptor): Option[(A, HalfEMCASDescriptor)] = {
      log.getOrElseNull(ref) match {
        case null =>
          // not in log
          this.readIntoLog(ref, log) match {
            case null =>
              None // None means rollback needed
            case newLog =>
              val a: A = newLog.getOrElseNull(ref).cast[A].ov
              Some((a, newLog))
          }
        case hwd =>
          // found in log
          Some((hwd.cast[A].nv, log))
          // TODO: if hwd.readOnly, we may need to re-validate here
      }
    }

    final def readIntoLog[A](ref: MemoryLocation[A], log: HalfEMCASDescriptor): HalfEMCASDescriptor = {
      require(log.getOrElseNull(ref) eq null)
      val hwd = this.readIntoHwd(ref)
      val newLog = log.add(hwd)
      if (hwd.version > newLog.validTs) {
        // this returns null if we need to roll back
        // (and we pass on the null to our caller):
        this.validateAndTryExtend(newLog)
      } else {
        newLog
      }
    }

    final def tryPerform2(desc: HalfEMCASDescriptor): Boolean = {
      if (desc.readOnly) {
        // we've validated each read,
        // so nothing to do here
        true
        // TODO: unconditional CAS-es and
        // TODO: direct reads may cause problems!
      } else {
        val finalDesc = this.addVersionCas(desc)
        assert(finalDesc.map.size == (desc.map.size + 1))
        this.tryPerform(finalDesc)
      }
    }

    // TODO: rename (maybe `addCasFromInitial`?)
    final def addCas[A](desc: HalfEMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A): HalfEMCASDescriptor =
      this.addCasWithVersion(desc, ref, ov = ov, nv = nv, version = Version.Start)

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

    private[mcas] final def validate(desc: HalfEMCASDescriptor): Boolean = {
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

    private[choam] final def addAll(to: HalfEMCASDescriptor, from: HalfEMCASDescriptor): HalfEMCASDescriptor = {
      HalfEMCASDescriptor.merge(to, from, this)
    }

    final def tryPerformSingleCas[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      val d0 = this.start()
      val d1 = this.readIntoLog(ref, d0)
      assert(d1 ne null)
      val hwd = d1.getOrElseNull(ref)
      assert(hwd ne null)
      if (equ(hwd.ov, ov)) {
        val d2 = d1.overwrite(hwd.withNv(nv))
        this.tryPerform2(d2)
      } else {
        false
      }
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

  private[mcas] final def impossibleOp[A, B](
    ref: MemoryLocation[_],
    a: HalfWordDescriptor[A],
    b: HalfWordDescriptor[B]
  ): Nothing = {
    throw new ImpossibleOperation(ref, a, b)
  }
}
