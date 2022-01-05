/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

// TODO: rename to EMCASThreadContext
private final class ThreadContext(
  global: GlobalContext,
  private[mcas] val tid: Long,
  val impl: EMCAS.type
) extends MCAS.ThreadContext {

  private[this] var commits: Int =
    0

  private[this] var retries: Int =
    0

  private[this] var markerUsedCount: Int =
    0

  private[this] var maxReuseEver: Int =
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
    if (this.markerUsedCount > this.maxReuseEver) {
      // TODO: this is not exactly correct, because
      // TODO: even if we `getReusableWeakRef` from
      // TODO: this thread context, we do not necessarily
      // TODO: use it (the CAS to install it may fail)
      this.maxReuseEver = this.markerUsedCount
    }
    this.markerUsedCount = 0
    mark // caller MUST hold a strong ref
  }

  // TODO: this is a hack
  private[this] var statistics: Map[AnyRef, AnyRef] =
    Map.empty

  // NB: it is a `val`, not a `def`
  private[choam] final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override def tryPerform(desc: HalfEMCASDescriptor): Boolean =
    impl.tryPerform(desc, this)

  final override def read[A](loc: MemoryLocation[A]): A =
    impl.read(loc, this)

  final override def toString: String = {
    s"ThreadContext(global = ${this.global}, tid = ${this.tid})"
  }

  private[choam] final override def recordCommit(retries: Int): Unit = {
    // TODO: opaque
    this.commits += 1
    this.retries += retries
  }

  /** Only for testing/benchmarking */
  private[choam] def getCommitsAndRetries(): (Int, Int) = {
    // TODO: opaque
    (this.commits, this.retries)
  }

  /** Only for testing/benchmarking */
  private[choam] final override def maxReusedWeakRefs(): Int = {
    // TODO: opaque
    this.maxReuseEver
  }

  /** Only for testing/benchmarking */
  private[choam] final override def supportsStatistics: Boolean = {
    true
  }

  /** Only for testing/benchmarking */
  private[choam] final override def getStatistics(): Map[AnyRef, AnyRef] = {
    this.statistics
  }

  /** Only for testing/benchmarking */
  private[choam] final override def setStatistics(stats: Map[AnyRef, AnyRef]): Unit = {
    this.statistics = stats
  }
}
