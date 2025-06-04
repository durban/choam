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
package unsafe

import cats.effect.kernel.Async

import internal.mcas.{ Mcas, MemoryLocation, LogEntry }

private[choam] trait CanSuspendInF {

  def suspend[F[_]](
    mcasImpl: Mcas,
    mcasCtx: Mcas.ThreadContext,
  )(implicit F: Async[F]): F[Unit]
}

sealed trait InRxn  {
  private[choam] def currentContext(): Mcas.ThreadContext
  private[choam] def initCtx(c: Mcas.ThreadContext): Unit
  private[choam] def invalidateCtx(): Unit
  private[choam] def imperativeRetry(): Option[CanSuspendInF]
  private[choam] def readRef[A](ref: MemoryLocation[A]): A
  private[choam] def writeRef[A](ref: MemoryLocation[A], nv: A): Unit
  private[choam] def updateRef[A](ref: MemoryLocation[A], f: A => A): Unit
  private[choam] def imperativeTentativeRead[A](ref: MemoryLocation[A]): A
  private[choam] def imperativeTicketRead[A](ref: MemoryLocation[A]): Ticket[A]
  private[choam] def imperativeTicketWrite[A](hwd: LogEntry[A], newest: A): Unit
  private[choam] def imperativeCommit(): Boolean
  private[choam] def beforeSuspend(): Unit
  private[choam] def beforeResult(): Unit
}

object InRxn {
  private[choam] trait UnsealedInRxn extends InRxn
}
