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

// JMX needs these to be named exactly like this;
// the MBean is registered in the `Emcas`
// constructor (if enabled).

private sealed trait EmcasJmxStatsMBean {
  def getCommits(): Long
  def getRetries(): Long
  def getRetriesPerCommit(): Double
  def getMaxRetriesPerCommit(): Long
  def getThreadContextCount(): Int
  def getMaxReusedWeakRefs(): Int
}

private final class EmcasJmxStats(impl: Emcas) extends EmcasJmxStatsMBean {

  final override def getCommits(): Long =
    impl.getRetryStats().commits

  final override def getRetries(): Long =
    impl.getRetryStats().retries

  final override def getRetriesPerCommit(): Double = {
    val c = this.getCommits()
    if (c != 0L) {
      val r = this.getRetries()
      r.toDouble / c.toDouble
    } else {
      Double.NaN
    }
  }

  final override def getMaxRetriesPerCommit(): Long = {
    impl.getRetryStats().maxRetries
  }

  final override def getThreadContextCount(): Int = {
    impl.threadContextCount()
  }

  final override def getMaxReusedWeakRefs(): Int = {
    impl.maxReusedWeakRefs()
  }
}
