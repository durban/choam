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
package hmcas

import java.util.concurrent.ThreadLocalRandom

final class HmcasThreadContext(
  final override val impl: Hmcas,
  final override val refIdGen: RefIdGen, // NB: it is a `val`, not a `def`
) extends Mcas.UnsealedThreadContext {

  final override type START = MutDescriptor

  final override def start(): AbstractDescriptor.Aux[MutDescriptor] = ???

  final override def readDirect[A](ref: MemoryLocation[A]): A = ???

  final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] = ???

  private[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long = ???

  final override def validateAndTryExtend(
    desc: AbstractDescriptor,
    hwd: LogEntry[_],
  ): AbstractDescriptor.Aux[desc.D] = ???

  private[mcas] final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long = ???

  final override val random: ThreadLocalRandom =
    ThreadLocalRandom.current()

  final override val buffer16BImpl: Array[Byte] =
    new Array[Byte](16)
}

object HmcasThreadContext {
}
