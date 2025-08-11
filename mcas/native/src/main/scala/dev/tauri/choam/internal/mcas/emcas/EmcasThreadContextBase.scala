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

private[mcas] abstract class EmcasThreadContextBase {
  protected[this] final def getCommitsO(): Long = ???
  protected[this] final def getRetriesO(): Long = ???
  protected[this] final def getExtensionsO(): Long = ???
  protected[this] final def getMcasAttemptsO(): Long = ???
  protected[this] final def getCommittedRefsO(): Long = ???
  protected[this] final def getCyclesDetectedO(): Int = ???
  protected[this] final def getMaxRetriesO(): Long = ???
  protected[this] final def getMaxCommittedRefsO(): Int = ???
  protected[this] final def getMaxBloomFilterSizeO(): Int = ???
  protected[emcas] final def recordEmcasFinalizedO(): Unit = ???
  protected[this] final def recordCommitO(retries: Int, committedRefs: Int, descExtensions: Int): Unit = ???
  protected[this] final def recordCycleDetectedO(bloomFilterSize: Int): Unit = ???
  protected[this] final def getMaxReuseEverP(): Int = ???
  protected[this] final def getMaxReuseEverO(): Int = ???
  protected[this] final def setMaxReuseEverP(nv: Int): Unit = ???
  protected[this] final def _getStatisticsP(): Map[AnyRef, AnyRef] = ???
  protected[this] final def _getStatisticsO(): Map[AnyRef, AnyRef] = ???
  protected[this] final def _setStatisticsP(nv: Map[AnyRef, AnyRef]): Unit = ???
}
