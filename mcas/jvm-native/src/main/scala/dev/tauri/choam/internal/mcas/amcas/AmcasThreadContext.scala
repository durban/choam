/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package amcas

import java.util.concurrent.ThreadLocalRandom

private final class AmcasThreadContext(
  final override val impl: Amcas,
  final override val refIdGen: RefIdGen, // NB: it is a `val`, not a `def`
) extends Mcas.UnsealedThreadContext {

  final override type START = MutDescriptor

  final override def start(): AbstractDescriptor.Aux[MutDescriptor] = {
    sys.error("TODO")
  }

  final override def readDirect[A](ref: MemoryLocation[A]): A = {
    sys.error("TODO")
  }

  final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] = {
    sys.error("TODO")
  }

  private[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long = {
    sys.error("TODO")
  }

  final override def validateAndTryExtend(
    desc: AbstractDescriptor,
    hwd: LogEntry[_],
  ): AbstractDescriptor.Aux[desc.D] = {
    sys.error("TODO")
  }

  private[mcas] final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long = {
    sys.error("TODO")
  }

  final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override val buffer16BImpl: Array[Byte] =
    new Array[Byte](16)
}
