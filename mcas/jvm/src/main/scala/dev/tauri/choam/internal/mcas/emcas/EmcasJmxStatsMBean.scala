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
package emcas

import java.util.concurrent.atomic.AtomicReference

// JMX needs these to be named exactly like this;
// the MBean is registered in the `Emcas`
// constructor (if enabled).

private sealed trait EmcasJmxStatsMBean {
  def getCommits(): Long
  def getRetries(): Long
  def getAvgRetriesPerCommit(): Double
  def getAvgTriesPerCommit(): Double
  def getMaxRetriesPerCommit(): Long
  def getMaxTriesPerCommit(): Long
  def getAvgLogSize(): Double
  def getMaxLogSize(): Long
  def getThreadContextCount(): Int
  def getMaxReusedWeakRefs(): Int
}

private final class EmcasJmxStats(impl: Emcas) extends EmcasJmxStatsMBean {

  private[this] final val cacheTimeoutNanos =
    1000000L // 1ms

  private[this] val _cachedStats: AtomicReference[(Mcas.RetryStats, Long)] =
    new AtomicReference

  private[this] final def getStats(): Mcas.RetryStats = {
    this._cachedStats.get() match {
      case null =>
        this.collectAndSaveStats(null)
      case tup @ (stats, timestamp) =>
        val now = System.nanoTime()
        if ((now - timestamp) > cacheTimeoutNanos) {
          this.collectAndSaveStats(tup)
        } else {
          stats
        }
    }
  }

  private[this] final def collectAndSaveStats(ov: (Mcas.RetryStats, Long)): Mcas.RetryStats = {
    val s = this.impl.getRetryStats()
    val ts = System.nanoTime()
    val tup = (s, ts)
    val wit = this._cachedStats.compareAndExchange(ov, tup)
    if (wit eq ov) {
      s
    } else {
      wit._1
    }
  }

  final override def getCommits(): Long =
    this.getStats().commits

  final override def getRetries(): Long =
    this.getStats().retries

  final override def getAvgRetriesPerCommit(): Double = {
    val c = this.getCommits()
    if (c != 0L) {
      val r = this.getRetries()
      r.toDouble / c.toDouble
    } else {
      Double.NaN
    }
  }

  final override def getAvgTriesPerCommit(): Double = {
    val c = this.getCommits()
    if (c != 0L) {
      val r = this.getRetries()
      (r + c).toDouble / c.toDouble
    } else {
      Double.NaN
    }
  }

  final override def getMaxRetriesPerCommit(): Long = {
    this.getStats().maxRetries
  }

  final override def getMaxTriesPerCommit(): Long = {
    this.getMaxRetriesPerCommit() + 1L
  }

  final override def getAvgLogSize(): Double = {
    val c = this.getCommits()
    if (c != 0L) {
      val allCommittedRefs = this.getStats().committedRefs
      allCommittedRefs.toDouble / c.toDouble
    } else {
      Double.NaN
    }
  }

  final override def getMaxLogSize(): Long = {
    this.getStats().maxCommittedRefs
  }

  final override def getThreadContextCount(): Int = {
    impl.threadContextCount()
  }

  final override def getMaxReusedWeakRefs(): Int = {
    impl.maxReusedWeakRefs() // TODO: this should be in the same stats object
  }
}
