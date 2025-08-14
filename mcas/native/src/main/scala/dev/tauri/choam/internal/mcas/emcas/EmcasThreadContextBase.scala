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

import scala.scalanative.annotation.alwaysinline

@nowarn("cat=unused-privates")
private[mcas] abstract class EmcasThreadContextBase {

  // intentionally non-volatile, see below
  private[this] var _commits: Long = _
  private[this] var _retries: Long = _
  private[this] var _extensions: Long = _
  private[this] var _mcasAttempts: Long = _
  private[this] var _committedRefs: Long = _
  private[this] var _cyclesDetected: Int = _
  private[this] var _maxReuseEver: Int = _
  private[this] var _maxRetriesEver: Long = _
  private[this] var _maxCommittedRefsEver: Int = _
  private[this] var _maxBloomFilterSize: Int = _
  private[this] var _statistics: Map[AnyRef, AnyRef] = Map.empty

  @alwaysinline
  private[this] final def atomicCommits: AtomicLongHandle =
    AtomicLongHandle(this, "_commits")

  @alwaysinline
  private[this] final def atomicRetries: AtomicLongHandle =
    AtomicLongHandle(this, "_retries")

  @alwaysinline
  private[this] final def atomicExtensions: AtomicLongHandle =
    AtomicLongHandle(this, "_extensions")

  @alwaysinline
  private[this] final def atomicMcasAttempts: AtomicLongHandle =
    AtomicLongHandle(this, "_mcasAttempts")

  @alwaysinline
  private[this] final def atomicCommittedRefs: AtomicLongHandle =
    AtomicLongHandle(this, "_committedRefs")

  @alwaysinline
  private[this] final def atomicCyclesDetected: AtomicIntHandle =
    AtomicIntHandle(this, "_cyclesDetected")

  @alwaysinline
  private[this] final def atomicMaxReuseEver: AtomicIntHandle =
    AtomicIntHandle(this, "_maxReuseEver")

  @alwaysinline
  private[this] final def atomicMaxRetriesEver: AtomicLongHandle =
    AtomicLongHandle(this, "_maxRetriesEver")

  @alwaysinline
  private[this] final def atomicMaxCommittedRefsEver: AtomicIntHandle =
    AtomicIntHandle(this, "_maxCommittedRefsEver")

  @alwaysinline
  private[this] final def atomicMaxBloomFilterSize: AtomicIntHandle =
    AtomicIntHandle(this, "_maxBloomFilterSize")

  @alwaysinline
  private[this] final def atomicStatistics: AtomicHandle[Map[AnyRef, AnyRef]] =
    AtomicHandle(this, "_statistics")

  protected[this] final def getCommitsO(): Long =
    atomicCommits.getOpaque

  protected[this] final def getRetriesO(): Long =
    atomicRetries.getOpaque

  protected[this] final def getExtensionsO(): Long =
    atomicExtensions.getOpaque

  protected[this] final def getMcasAttemptsO(): Long =
    atomicMcasAttempts.getOpaque

  protected[this] final def getCommittedRefsO(): Long =
    atomicCommittedRefs.getOpaque

  protected[this] final def getCyclesDetectedO(): Int =
    atomicCyclesDetected.getOpaque

  protected[this] final def getMaxRetriesO(): Long =
    atomicMaxRetriesEver.getOpaque

  protected[this] final def getMaxCommittedRefsO(): Int =
    atomicMaxCommittedRefsEver.getOpaque

  protected[this] final def getMaxBloomFilterSizeO(): Int =
    atomicMaxBloomFilterSize.getOpaque

  protected[emcas] final def recordEmcasFinalizedO(): Unit = {
    atomicMcasAttempts.setOpaque(this._mcasAttempts + 1L)
  }

  protected[this] final def recordCommitO(retries: Int, committedRefs: Int, descExtensions: Int): Unit = {
    // Only one thread writes, so `+=`-like
    // increment is fine here. There is a
    // race though: a reader can read values
    // of commits and retries which do not
    // "belong" together (e.g., a current
    // value for commits, and an old one
    // for retries). But this is just for
    // statistical and informational purposes,
    // so we don't really care.
    atomicCommits.setOpaque(this._commits + 1L)
    val retr: Long = retries.toLong
    atomicRetries.setOpaque(this._retries + retr)
    atomicExtensions.setOpaque(this._extensions + descExtensions)
    atomicCommittedRefs.setOpaque(this._committedRefs + committedRefs.toLong)
    if (retr > this._maxRetriesEver) {
      atomicMaxRetriesEver.setOpaque(retr)
    }
    if (committedRefs > this._maxCommittedRefsEver) {
      atomicMaxCommittedRefsEver.setOpaque(committedRefs)
    }
  }

  protected[this] final def recordCycleDetectedO(bloomFilterSize: Int): Unit = {
    atomicCyclesDetected.setOpaque(this._cyclesDetected + 1)
    if (bloomFilterSize > this._maxBloomFilterSize) {
      atomicMaxBloomFilterSize.setOpaque(bloomFilterSize)
    }
  }

  protected[this] final def getMaxReuseEverP(): Int = {
    this._maxReuseEver
  }

  protected[this] final def getMaxReuseEverO(): Int = {
    atomicMaxReuseEver.getOpaque
  }

  protected[this] final def setMaxReuseEverP(nv: Int): Unit = {
    this._maxReuseEver = nv
  }

  protected[this] final def _getStatisticsP(): Map[AnyRef, AnyRef] = {
    this._statistics
  }

  protected[this] final def _getStatisticsO(): Map[AnyRef, AnyRef] = {
    atomicStatistics.getOpaque
  }

  protected[this] final def _setStatisticsP(nv: Map[AnyRef, AnyRef]): Unit = {
    this._statistics = nv
  }
}
