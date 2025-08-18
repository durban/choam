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

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

/** Ctor must be called on the thread of the new instance! */
private final class EmcasThreadContext(
  final override val impl: Emcas,
  final override val refIdGen: RefIdGen, // NB: it is a `val`, not a `def`
) extends EmcasThreadContextBase
  with Mcas.UnsealedThreadContext {

  final override type START = MutDescriptor

  private[this] val thread: Thread =
    Thread.currentThread()

  private[mcas] val tid: Long =
    thread.getId()

  // NB: it is a `val`, not a `def`
  final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override val buffer16B: Array[Byte] =
    new Array[Byte](16)

  // NB: it is a `val`, not a `def`
  private[choam] final override val stripeId: Int =
    java.lang.Long.remainderUnsigned(Consts.staffordMix13(tid), this.stripes.toLong).toInt

  private[this] var markerUsedCount: Int =
    0

  private[this] final val maxMarkerUsedCount =
    4096

  /**
   * When storing a `EmcasWordDesc` into a ref, `EMCAS` first
   * tries to reuse an existing weakref and marker in the ref
   * (see there). If that is not possible, we need another
   * (full) weakref and mark.
   *
   * We could create a fresh one each time, but that could cause
   * a lot of `WeakReference`s to exist simultaneously in
   * some scenarios (e.g., when creating a lot of refs, which
   * are only used once or twice). A lot of `WeakReference`s
   * seem to force the GC to work much harder, which causes
   * bad performance.
   *
   * So, instead of creating a fresh weakref and mark, we ask
   * the `ThreadContext`, to give us a (possibly) reused one.
   * Here we cache a weakref, and reuse it for a while. We don't
   * want to reuse it forever (even if it's not cleared), since
   * each ref which uses the same marker is "bound" together
   * regarding their descriptor cleanup. So, occasionally we create
   * a fresh one (see `markerUsedCount`). (Using the same marker
   * for a limited number of refs is probably not a big deal.)
   */
  private[this] var markerWeakRef: WeakReference[AnyRef] =
    null

  // This is ugly: `getReusableWeakRef` MUST be called
  // immediately after `getReusableMarker`, and the caller
  // MUST hold a strong ref to the return value of
  // `getReusableMarker`. (Otherwise there would be a race
  // with the GC; if the GC wins, we get an empty WeakRef.)
  // But this way we can avoid allocating a 2-tuple to
  // hold both the marker and weakref.

  private[mcas] final def getReusableMarker(): AnyRef = {
    val mwr = this.markerWeakRef
    if (mwr eq null) {
      // nothing to reuse, create new:
      this.createReusableMarker()
    } else {
      val mark = mwr.get()
      if (mark eq null) {
        // nothing to reuse, create new:
        this.createReusableMarker()
      } else {
        // maybe we can reuse it:
        val newMuc = this.markerUsedCount + 1
        this.markerUsedCount = newMuc
        if (newMuc == maxMarkerUsedCount) {
          // we used it too much, create new:
          this.createReusableMarker()
        } else {
          // OK, reuse it:
          mark
        }
      }
    }
  }

  private[mcas] final def getReusableWeakRef(): WeakReference[AnyRef] = {
    this.markerWeakRef
  }

  private[this] final def createReusableMarker(): AnyRef = {
    val mark = new McasMarker
    this.markerWeakRef = new WeakReference(mark)
    if (this.markerUsedCount > this.getMaxReuseEverP()) {
      // TODO: this is not exactly correct, because
      // TODO: even if we `getReusableWeakRef` from
      // TODO: this thread context, we do not necessarily
      // TODO: use it (the CAS to install it may fail)
      this.setMaxReuseEverP(this.markerUsedCount)
    }
    this.markerUsedCount = 0
    mark // caller MUST hold a strong ref
  }

  private[emcas] final def isCurrentContext(): Boolean = {
    this.thread eq Thread.currentThread()
  }

  final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long =
    impl.tryPerformInternal(desc, this, optimism)

  final override def readDirect[A](ref: MemoryLocation[A]): A =
    impl.readDirect(ref, this)

  final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] = {
    val hwd = impl.readIntoHwd(ref, this)
    _assert(hwd.readOnly)
    hwd
  }

  protected[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long =
    impl.readVersion(ref, this)

  final override def start(): MutDescriptor =
    MutDescriptor.newEmptyFromVer(this.impl.getCommitTs())

  final override def startSnap(): Descriptor =
    Descriptor.emptyFromVer(this.impl.getCommitTs())

  final override def validateAndTryExtend(
    desc: AbstractDescriptor,
    hwd: LogEntry[?],
  ): AbstractDescriptor.Aux[desc.D] = {
    desc.validateAndTryExtendVer(this.impl.getCommitTs(), this, hwd)
  }

  final override def toString: String =
    s"ThreadContext(impl = ${this.impl}, tid = ${this.tid})"

  private[choam] final override def recordCommit(retries: Int, committedRefs: Int, descExtensions: Int): Unit = {
    this.recordCommitO(retries, committedRefs, descExtensions)
  }

  private[emcas] final def recordCycleDetected(bloomFilterSize: Int): Unit = {
    this.recordCycleDetectedO(bloomFilterSize)
  }

  private[choam] def getRetryStats(): Mcas.RetryStats = {
    Mcas.RetryStats(
      commits = this.getCommitsO(),
      retries = this.getRetriesO(),
      extensions = this.getExtensionsO(),
      mcasAttempts = this.getMcasAttemptsO(),
      committedRefs = this.getCommittedRefsO(),
      cyclesDetected = this.getCyclesDetectedO().toLong,
      maxRetries = this.getMaxRetriesO(),
      maxCommittedRefs = this.getMaxCommittedRefsO(),
      maxBloomFilterSize = this.getMaxBloomFilterSizeO(),
    )
  }

  private[choam] final override def maxReusedWeakRefs(): Int = {
    this.getMaxReuseEverO()
  }

  private[choam] final override def supportsStatistics: Boolean = {
    true
  }

  private[choam] final override def getStatisticsP(): Map[AnyRef, AnyRef] = {
    this._getStatisticsP()
  }

  private[choam] final override def getStatisticsO(): Map[AnyRef, AnyRef] = {
    this._getStatisticsO()
  }

  private[choam] final override def setStatisticsP(stats: Map[AnyRef, AnyRef]): Unit = {
    this._setStatisticsP(stats)
  }
}
