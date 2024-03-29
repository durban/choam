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

import java.util.concurrent.ThreadLocalRandom

/** Common interface for MCAS (i.e., k-CAS) implementations */
sealed trait Mcas {

  /** Returns the context associated with the current thread */
  def currentContext(): Mcas.ThreadContext

  /** True iff `this` can be used to perform ops on arbitrary refs */
  private[choam] def isThreadSafe: Boolean

  /** Only for testing/benchmarking */
  private[choam] def getRetryStats(): Mcas.RetryStats = {
    // implementations should override if
    // they collect statistics
    Mcas.RetryStats(0L, 0L, 0L, 0L, 0L)
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

object Mcas extends McasCompanionPlatform { self =>

  private[mcas] trait UnsealedMcas extends Mcas

  private[mcas] trait UnsealedThreadContext extends ThreadContext

  sealed trait ThreadContext {

    // abstract:

    /** The `Mcas` instance from which this context was retrieved */
    def impl: Mcas

    /**
     * Starts building a descriptor
     * (its `.validTs` will be the
     * current global sersion).
     */
    def start(): Descriptor

    private[mcas] def addVersionCas(desc: Descriptor): Descriptor

    /**
     * @return the current value of `ref`, as
     *         if read by `readIntoHwd(ref).nv`.
     */
    def readDirect[A](ref: MemoryLocation[A]): A

    /**
     * @return the current value and version of `ref`
     *         in a word descriptor object. The
     *         value and version are guaranteed to be
     *         consistent with each other. The descriptor's
     *         `.ov` and `.nv` are guaranteed to be the same.
     */
    def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A]

    /**
     * @return the current version of `ref`, as if
     *         read by `readIntoHwd(ref).version`.
     */
    private[mcas] def readVersion[A](ref: MemoryLocation[A]): Long

    def validateAndTryExtend(
      desc: Descriptor,
      hwd: HalfWordDescriptor[_], // may be null
    ): Descriptor

    /**
     * Directly tries to perform the k-CAS described by `desc`
     *
     * @return either `EmcasStatus.Successful` (if successful);
     *         `EmcasStatus.FailedVal` (if failed due to an
     *         expected value not matching); or the current
     *         global version (if failed due to the version
     *         being newer than `desc.validTs`).
     */
    private[mcas] def tryPerformInternal(desc: Descriptor): Long

    /** @return a `ThreadLocalRandom` valid for the current thread */
    def random: ThreadLocalRandom

    /** @return the `RefIdGen` of the current thread (or a thread-safe one) */
    def refIdGen: RefIdGen

    // concrete:

    /** Utility to first try to read from the log, and only from the ref if not found */
    final def readMaybeFromLog[A](ref: MemoryLocation[A], log: Descriptor): Option[(A, Descriptor)] = {
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
      }
    }

    private[this] final def readIntoLog[A](ref: MemoryLocation[A], log: Descriptor): Descriptor = {
      require(log.getOrElseNull(ref) eq null)
      val hwd = this.readIntoHwd(ref)
      val newLog = log.add(hwd)
      if (!newLog.isValidHwd(hwd)) {
        // this returns null if we need to roll back
        // (and we pass on the null to our caller):
        val res = this.validateAndTryExtend(newLog, hwd = null)
        if (res ne null) {
          assert(res.isValidHwd(hwd))
        }
        res
      } else {
        newLog
      }
    }

    /**
     * Tries to perform the ops in `desc`, with adding a
     * version-CAS (if not read-only).
     *
     * @return either `EmcasStatus.Successful` (if successful);
     *         `EmcasStatus.FailedVal` (if failed due to an
     *         expected value not matching); or the current
     *         global version (if failed due to the version
     *         being newer than `desc.validTs`).
     */
    final def tryPerform(desc: Descriptor): Long = {
      if (desc.readOnly) {
        // we've validated each read,
        // so nothing to do here
        McasStatus.Successful
      } else {
        // TODO: Could we ignore read-only HWDs
        // TODO: in the log? They're validated,
        // TODO: and the version CAS _should_
        // TODO: catch any concurrent changes(?)
        val finalDesc = this.addVersionCas(desc)
        val res = this.tryPerformInternal(finalDesc)
        assert((res == McasStatus.Successful) || (res == McasStatus.FailedVal) || Version.isValid(res))
        res
      }
    }

    /** Like `tryPerform`, but returns whether it was successful */
    final def tryPerformOk(desc: Descriptor): Boolean = {
      tryPerform(desc) == McasStatus.Successful
    }

    final def addCasFromInitial[A](desc: Descriptor, ref: MemoryLocation[A], ov: A, nv: A): Descriptor =
      this.addCasWithVersion(desc, ref, ov = ov, nv = nv, version = Version.Start)

    final def addCasWithVersion[A](
      desc: Descriptor,
      ref: MemoryLocation[A],
      ov: A,
      nv: A,
      version: Long
    ): Descriptor = {
      val wd = HalfWordDescriptor(ref, ov, nv, version)
      desc.add(wd)
    }

    /**
     * Tries to (re)validate `desc` based on the current
     * versions of the refs it contains.
     *
     * @return true, iff `desc` is still valid.
     */
    private[mcas] final def validate(desc: Descriptor): Boolean = {
      desc.revalidate(this)
    }

    private[mcas] final def validateHwd[A](hwd: HalfWordDescriptor[A]): Boolean = {
      hwd.revalidate(this)
    }

    /**
     * @return a snapshot of `desc`.
     */
    final def snapshot(desc: Descriptor): Descriptor =
      desc

    /**
     * Merges disjoint descriptors `to` and `from`.
     *
     * @return The merged descriptor, which contains
     *         all the ops either in `to` or `from`.
     */
    private[choam] final def addAll(to: Descriptor, from: Descriptor): Descriptor = {
      Descriptor.merge(to, from, this)
    }

    /**
     * Tries to perform a "bare" 1-CAS, without
     * changing the global version. This breaks
     * opacity guarantees! It may change the value
     * of a ref without changing its version!
     */
    final def singleCasDirect[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      assert(!equ(ov, nv))
      val hwd = this.readIntoHwd(ref)
      val d0 = this.start() // do this after reading, so version is deemed valid
      assert(d0.isValidHwd(hwd))
      if (equ(hwd.ov, ov)) {
        // create a (dangerous) descriptor, which will set
        // the new version to `validTs` (which may equal the
        // old version):
        val d1 = d0.add(hwd.withNv(nv)).withNoNewVersion
        assert(d1.newVersion == d1.validTs)
        // we're intentionally NOT having a version-CAS:
        this.tryPerformInternal(d1) == McasStatus.Successful
      } else {
        false
      }
    }

    /**
     * Tries to perform a 1-CAS, but
     * also handles versions. Equivalent
     * to creating a descriptor containing a
     * single op, and calling `tryPerformOk`
     * on it (but may be more efficient).
     */
    final def tryPerformSingleCas[A](ref: MemoryLocation[A], ov: A, nv: A): Boolean = {
      // TODO: this could be optimized (probably)
      val d0 = this.start()
      val d1 = this.readIntoLog(ref, d0)
      assert(d1 ne null)
      val hwd = d1.getOrElseNull(ref)
      assert(hwd ne null)
      if (equ(hwd.ov, ov)) {
        val d2 = d1.overwrite(hwd.withNv(nv))
        this.tryPerform(d2) == McasStatus.Successful
      } else {
        false
      }
    }

    // statistics/testing/benchmarking:

    /** Only for testing */
    private[mcas] final def builder(): Builder = {
      new Builder(this, this.start())
    }

    /** Only for testing/benchmarking */
    private[choam] def recordCommit(@unused retries: Int, @unused committedRefs: Int): Unit = {
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

  private[choam] final case class RetryStats(
    /** The number of successfully committed `Rxn`s */
    commits: Long,
    /** The number of retries overall */
    retries: Long,
    /** The sum of the number of `Ref`s the committed `Rxn`s touched */
    committedRefs: Long,
    /** The highest number of retries one `Rxn` had to perform */
    maxRetries: Long,
    /** The size (touched `Ref`s) of the biggest `Rxn` that committed */
    maxCommittedRefs: Long,
  )

  /** Only for testing */
  private[mcas] final class Builder(
    private[this] val ctx: ThreadContext,
    private[this] val desc: Descriptor,
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
