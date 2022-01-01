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
