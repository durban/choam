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

    // abstract:

    def start(): HalfEMCASDescriptor

    protected[mcas] def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor

    def readDirect[A](ref: MemoryLocation[A]): A

    def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A]

    protected[mcas] def readVersion[A](ref: MemoryLocation[A]): Long

    protected[choam] def validateAndTryExtend(desc: HalfEMCASDescriptor): HalfEMCASDescriptor

    // TODO: -> tryPerformInternal
    def tryPerform(desc: HalfEMCASDescriptor): Long

    def tryPerformInternal(desc: HalfEMCASDescriptor): Long =
      tryPerform(desc)

    private[choam] def random: ThreadLocalRandom

    // concrete:

    /** Utility to first try to read from the log, and only from the ref if not found */
    final def readMaybeFromLog[A](ref: MemoryLocation[A], log: HalfEMCASDescriptor): Option[(A, HalfEMCASDescriptor)] = {
      log.getOrElseNull(ref) match {
        case null =>
          // not in log
          this.readIntoLog(ref, log) match {
            case null =>
              None // None means rollback needed
            case newLog =>
              val a: A = newLog.getOrElseNull(ref).nv
              Some((a, newLog))
          }
        case hwd =>
          // found in log
          Some((hwd.cast[A].nv, log))
          // TODO: if hwd.readOnly, we may need to re-validate here
      }
    }

    private[this] final def readIntoLog[A](ref: MemoryLocation[A], log: HalfEMCASDescriptor): HalfEMCASDescriptor = {
      require(log.getOrElseNull(ref) eq null)
      val hwd = this.readIntoHwd(ref)
      val newLog = log.add(hwd)
      if (!newLog.isValidHwd(hwd)) {
        // this returns null if we need to roll back
        // (and we pass on the null to our caller):
        val res = this.validateAndTryExtend(newLog)
        if (res ne null) {
          assert(res.isValidHwd(hwd))
        }
        res
      } else {
        newLog
      }
    }

    // TODO: -> tryPerform
    final def tryPerform2(desc: HalfEMCASDescriptor): Long = {
      if (desc.readOnly) {
        // we've validated each read,
        // so nothing to do here
        EmcasStatus.Successful
        // TODO: unconditional CAS-es and
        // TODO: direct reads may cause problems!
      } else {
        val finalDesc = this.addVersionCas(desc)
        assert(finalDesc.map.size == (desc.map.size + 1))
        val res = this.tryPerform(finalDesc)
        assert((res == EmcasStatus.Successful) || (res == EmcasStatus.FailedVal) || Version.isValid(res))
        res
      }
    }

    final def tryPerformOk(desc: HalfEMCASDescriptor): Boolean = {
      tryPerform2(desc) == EmcasStatus.Successful
    }

    final def addCasFromInitial[A](desc: HalfEMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A): HalfEMCASDescriptor =
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

    /**
     * Do a real 1-CAS, without commitTs.
     * This breaks opacity guarantees!
     * It may change the value of a ref
     * without changing the version!
     */
    final def singleCasDirect[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      assert(!equ(ov, nv))
      val hwd = this.readIntoHwd(ref)
      val d0 = this.start() // do this after reading, so version is deemed valid
      assert(d0.isValidHwd(hwd))
      if (equ(hwd.ov, ov)) {
        // creating a (dangerous) descriptor, which will set
        // the new version to `validTs` (which may equal the
        // old version):
        val d1 = d0.add(hwd.withNv(nv)).withNoNewVersion
        assert(d1.newVersion == d1.validTs)
        // we're intentionally NOT having a version-CAS:
        this.tryPerformInternal(d1) == EmcasStatus.Successful
      } else {
        false
      }
    }

    // TODO: do we even need this?
    final def tryPerformSingleCas[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      // TODO: this could be optimized (probably)
      val d0 = this.start()
      val d1 = this.readIntoLog(ref, d0)
      assert(d1 ne null)
      val hwd = d1.getOrElseNull(ref)
      assert(hwd ne null)
      if (equ(hwd.ov, ov)) {
        val d2 = d1.overwrite(hwd.withNv(nv))
        this.tryPerform2(d2) == EmcasStatus.Successful
      } else {
        false
      }
    }

    // statistics/testing/benchmarking:

    /** Only for testing */
    private[choam] def builder(): Builder = {
      new Builder(this, this.start())
    }

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

  private[choam] final class Builder(
    private[this] val ctx: ThreadContext,
    private[this] val desc: HalfEMCASDescriptor,
  ) {

    final def updateRef[A](ref: MemoryLocation[A], f: A => A): Builder = {
      this.ctx.readMaybeFromLog(ref, this.desc) match {
        case Some((ov, newDesc)) =>
          val nv = f(ov)
          val newestDesc = newDesc.overwrite(
            newDesc.getOrElseNull(ref).withNv(nv)
          )
          new Builder(this.ctx, newestDesc)
        case None =>
          throw new IllegalStateException("couldn't extend, rollback is necessary")
      }
    }

    final def casRef[A](ref: MemoryLocation[A], from: A, to: A): Builder = {
      this.tryCasRef(ref, from, to).getOrElse(
        throw new IllegalStateException("couldn't extend, rollback is necessary")
      )
    }

    final def tryCasRef[A](ref: MemoryLocation[A], from: A, to: A): Option[Builder] = {
      this.ctx.readMaybeFromLog(ref, this.desc).map {
        case (ov, newDesc) =>
          val newestDesc = if (equ(ov, from)) {
            newDesc.overwrite(newDesc.getOrElseNull(ref).withNv(to))
          } else {
            val oldHwd = newDesc.getOrElseNull(ref)
            val newHwd = HalfWordDescriptor(ref, from, to, oldHwd.version)
            newDesc.overwrite(newHwd)
          }
          new Builder(this.ctx, newestDesc)
      }
    }

    final def tryPerformOk(): Boolean = {
      this.ctx.tryPerformOk(this.desc)
    }
  }
}
