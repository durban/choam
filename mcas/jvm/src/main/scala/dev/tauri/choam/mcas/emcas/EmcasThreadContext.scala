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
package emcas

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

private final class EmcasThreadContext(
  global: GlobalContext,
  private[mcas] val tid: Long,
  val impl: Emcas.type
) extends EmcasThreadContextBase
  with Mcas.ThreadContext {

  // NB: it is a `val`, not a `def`
  private[choam] final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  private[this] var markerUsedCount: Int =
    0

  private[this] final val maxMarkerUsedCount =
    4096

  /**
   * When storing a `WordDescriptor` into a ref, `EMCAS` first
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
    if (this.markerWeakRef eq null) {
      // nothing to reuse, create new:
      this.createReusableMarker()
    } else {
      val mark = this.markerWeakRef.get()
      if (mark eq null) {
        // nothing to reuse, create new:
        this.createReusableMarker()
      } else {
        // maybe we can reuse it:
        this.markerUsedCount += 1
        if (this.markerUsedCount == maxMarkerUsedCount) {
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
    if (this.markerUsedCount > this.getMaxReuseEverPlain()) {
      // TODO: this is not exactly correct, because
      // TODO: even if we `getReusableWeakRef` from
      // TODO: this thread context, we do not necessarily
      // TODO: use it (the CAS to install it may fail)
      this.setMaxReuseEverPlain(this.markerUsedCount)
    }
    this.markerUsedCount = 0
    mark // caller MUST hold a strong ref
  }

  final override def tryPerformInternal(desc: HalfEMCASDescriptor): Long =
    impl.tryPerformInternal(desc, this)

  final override def readDirect[A](ref: MemoryLocation[A]): A =
    impl.readDirect(ref, this)

  final override def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
    val hwd = impl.readIntoHwd(ref, this)
    assert(hwd.readOnly)
    hwd
  }

  protected[mcas] final override def readVersion[A](ref: MemoryLocation[A]): Long =
    impl.readVersion(ref, this)

  final override def start(): HalfEMCASDescriptor =
    HalfEMCASDescriptor.emptyFromVer(this.global.commitTs.get())

  protected[mcas] final override def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
    desc // we increment the global commit version differently

  protected[choam] def validateAndTryExtend(
    desc: HalfEMCASDescriptor,
    hwd: HalfWordDescriptor[_],
  ): HalfEMCASDescriptor = {
    desc.validateAndTryExtendVer(this.global.commitTs.get(), this, hwd)
  }

  final override def toString: String =
    s"ThreadContext(global = ${this.global}, tid = ${this.tid})"

  private[choam] final override def recordCommit(fullRetries: Int, mcasRetries: Int): Unit = {
    this.recordCommitPlain(fullRetries, mcasRetries)
  }

  /** Only for testing/benchmarking */
  private[choam] def getRetryStats(): Mcas.RetryStats = {
    Mcas.RetryStats(
      commits = this.getCommitsOpaque(),
      fullRetries = this.getFullRetriesOpaque(),
      mcasRetries = this.getMcasRetriesOpaque(),
    )
  }

  /** Only for testing/benchmarking */
  private[choam] final override def maxReusedWeakRefs(): Int = {
    this.getMaxReuseEverOpaque()
  }

  /** Only for testing/benchmarking */
  private[choam] final override def supportsStatistics: Boolean = {
    true
  }

  /** Only for testing/benchmarking */
  private[choam] final override def getStatisticsPlain(): Map[AnyRef, AnyRef] = {
    this._getStatisticsPlain()
  }

  /** Only for testing/benchmarking */
  private[choam] final override def getStatisticsOpaque(): Map[AnyRef, AnyRef] = {
    this._getStatisticsOpaque()
  }

  /** Only for testing/benchmarking */
  private[choam] final override def setStatisticsPlain(stats: Map[AnyRef, AnyRef]): Unit = {
    this._setStatisticsPlain(stats)
  }
}
