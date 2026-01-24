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
package unsafe

import core.{ Rxn, Ref, InternalLocal, InternalLocalArray }
import internal.mcas.{ Mcas, MemoryLocation, LogEntry }

sealed trait InRoRxn {
  private[choam] def readRef[A](ref: MemoryLocation[A]): A
  private[choam] def readRefArray[A](arr: Ref.Array[A], idx: Int): A
  private[choam] def imperativeTentativeRead[A](ref: MemoryLocation[A]): A
  private[choam] def imperativeTentativeReadArray[A](arr: Ref.Array[A], idx: Int): A
  private[choam] def imperativeTicketRead[A](ref: MemoryLocation[A]): Ticket[A]
  private[choam] def imperativeTicketReadArray[A](arr: Ref.Array[A], idx: Int): Ticket[A]
  private[choam] def imperativeTicketValidate[A](hwd: LogEntry[A]): Unit
}

sealed trait InRxn extends InRoRxn {
  private[choam] def currentContext(): Mcas.ThreadContext
  private[choam] def initCtx(c: Mcas.ThreadContext): Unit
  private[choam] def invalidateCtx(): Unit
  private[choam] def imperativeRetry(): Option[CanSuspendInF]
  private[choam] def writeRef[A](ref: MemoryLocation[A], nv: A): Unit
  private[choam] def writeRefArray[A](arr: Ref.Array[A], idx: Int, nv: A): Unit
  private[choam] def updateRef[A](ref: MemoryLocation[A], f: A => A): Unit
  private[choam] def updateRefArray[A](arr: Ref.Array[A], idx: Int, f: A => A): Unit
  private[choam] def getAndSetRef[A](ref: MemoryLocation[A], nv: A): A
  private[choam] def imperativeTicketWrite[A](hwd: LogEntry[A], newest: A): Unit
  private[choam] def imperativeCommit(): Boolean
  private[choam] def beforeSuspend(): Unit
  private[choam] def beforeResult(): Unit
}

sealed trait InRxn2 extends InRxn { // TODO: this only exists because only embedUnsafe supports post-commit actions
  private[choam] def imperativePostCommit(pc: Rxn[Unit]): Unit
}

private[choam] object InRxn {
  private[choam] trait InterpState extends InRxn2 { // TODO: this is not really related to the imperative API
    private[choam] def localOrigin: RxnLocal.Origin
    private[choam] def registerLocal(local: InternalLocal): Unit
    private[choam] def removeLocal(local: InternalLocal): Unit
    private[choam] def localGetSlowPath(local: InternalLocal): AnyRef
    private[choam] def localSetSlowPath(local: InternalLocal, nv: AnyRef): Unit
    private[choam] def localGetArrSlowPath(local: InternalLocalArray, idx: Int): AnyRef
    private[choam] def localSetArrSlowPath(local: InternalLocalArray, idx: Int, nv: AnyRef): Unit
    private[choam] def localTakeSnapshotSlowPath(local: InternalLocal): AnyRef
    private[choam] def localTakeSnapshotArrSlowPath(local: InternalLocalArray): AnyRef
    private[choam] def localLoadSnapshotSlowPath(local: InternalLocal, snap: AnyRef): Unit
    private[choam] def localLoadSnapshotArrSlowPath(local: InternalLocalArray, snap: AnyRef): Unit
  }
}
