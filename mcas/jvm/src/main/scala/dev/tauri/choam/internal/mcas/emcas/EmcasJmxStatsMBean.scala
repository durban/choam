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

import java.util.concurrent.atomic.AtomicReference

// JMX needs these to be named exactly like this;
// the MBean is registered in the `Emcas`
// constructor (if enabled).

private[choam] sealed trait EmcasJmxStatsMBean {

  // Attributes:
  def getCommits(): Long
  def getRetries(): Long
  def getExtensions(): Long
  def getMcasAttempts(): Long
  def getCyclesDetected(): Long
  def getAvgRetriesPerCommit(): Double
  def getAvgTriesPerCommit(): Double
  def getAvgExtensionsPerCommit(): Double
  def getAvgCyclesPerMcasAttempt(): Double
  def getMaxRetriesPerCommit(): Long
  def getMaxTriesPerCommit(): Long
  def getAvgLogSize(): Double
  def getMaxLogSize(): Int
  def getMaxBloomFilterSize(): Int
  def getThreadContextCount(): Int
  def getMaxReusedWeakRefs(): Int

  // Attributes, but TODO: they use non-standard types
  def getMcasRetryStats(): Mcas.RetryStats
  def getExchangerStats(): Map[Long, Map[AnyRef, AnyRef]]

  // Operations:
  def checkConsistency(): String
}

private[choam] final class EmcasJmxStats(impl: Emcas) extends EmcasJmxStatsMBean {

  private[this] final val cacheTimeoutNanos =
    10000000L // 10ms

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
    val s = this.getMcasRetryStats()
    val ts = System.nanoTime()
    val tup = (s, ts)
    val wit = this._cachedStats.compareAndExchange(ov, tup)
    if (wit eq ov) {
      s
    } else {
      wit._1
    }
  }

  final override def getMcasRetryStats(): Mcas.RetryStats = {
    this.impl.getRetryStats()
  }

  final override def getExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    this.impl.collectExchangerStats()
  }

  final override def checkConsistency(): String = {
    val s = this.getStats()
    if ((s.commits + s.retries) >= (s.mcasAttempts - s.cyclesDetected)) {
      null // OK
    } else {
      s"""Stats(
      |  Commits = ${s.commits}
      |  Retries = ${s.retries}
      |  McasAttempts = ${s.mcasAttempts}
      |  CyclesDetected = ${s.cyclesDetected}
      |)""".stripMargin
    }
  }

  final override def getCommits(): Long =
    this.getStats().commits

  final override def getRetries(): Long =
    this.getStats().retries

  final override def getExtensions(): Long =
    this.getStats().extensions

  final override def getMcasAttempts(): Long =
    this.getStats().mcasAttempts

  final override def getCyclesDetected(): Long =
    this.getStats().cyclesDetected

  final override def getAvgRetriesPerCommit(): Double = {
    this.getStats().avgRetriesPerCommit
  }

  final override def getAvgTriesPerCommit(): Double = {
    this.getStats().avgTriesPerCommit
  }

  final override def getAvgExtensionsPerCommit(): Double = {
    this.getStats().avgExtensionsPerCommit
  }

  final override def getAvgCyclesPerMcasAttempt(): Double = {
    this.getStats().avgCyclesPerMcasAttempt
  }

  final override def getMaxRetriesPerCommit(): Long = {
    this.getStats().maxRetries
  }

  final override def getMaxTriesPerCommit(): Long = {
    this.getMaxRetriesPerCommit() + 1L
  }

  final override def getAvgLogSize(): Double = {
    this.getStats().avgLogSize
  }

  final override def getMaxLogSize(): Int = {
    this.getStats().maxLogSize
  }

  final override def getMaxBloomFilterSize(): Int = {
    this.getStats().maxBloomFilterSize
  }

  final override def getThreadContextCount(): Int = {
    impl.threadContextCount()
  }

  final override def getMaxReusedWeakRefs(): Int = {
    impl.maxReusedWeakRefs() // TODO: this should be in the same stats object
  }
}
