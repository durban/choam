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

import core.{ Ref, Rxn }

package object unsafe {

  /**
   * Extension methods for more convenient
   * handling of `Ref`s in an `atomically`
   * block.
   */
  implicit final class RefSyntax[A](private val self: Ref[A]) extends AnyVal {

    /** @see [[dev.tauri.choam.core.Ref.get]] */
    final def value(implicit ir: InRoRxn): A =
      ir.readRef(self.loc)

    /** @see [[dev.tauri.choam.core.Ref.set]] */
    final def value_=(nv: A)(implicit ir: InRxn): Unit =
      ir.writeRef(self.loc, nv)
  }

  implicit final class RefArraySyntax[A](private val self: Ref.Array[A]) extends AnyVal {

    final def apply(idx: Int)(implicit ir: InRoRxn): A =
      ir.readRefArray(self, idx)

    final def update(idx: Int, nv: A)(implicit ir: InRxn): Unit =
      ir.writeRefArray(self, idx, nv)
  }

  /** @see [[dev.tauri.choam.core.Ref.apply]] */
  final def newRef[A](
    initial: A,
    strategy: AllocationStrategy = AllocationStrategy.Default,
  )(implicit ir: InRxn): Ref[A] = {
    Ref.unsafe(initial, strategy, ir.currentContext().refIdGen)
  }

  /** @see [[dev.tauri.choam.core.Ref.array]] */
  final def newRefArray[A](
    size: Int,
    initial: A,
    strategy: AllocationStrategy = AllocationStrategy.Default,
  )(implicit ir: InRxn): Ref.Array[A] = {
    Ref.unsafeArray(size, initial, strategy, ir.currentContext().refIdGen)
  }

  /**
   * @see [[dev.tauri.choam.core.Ref.get]]
   * @see [[dev.tauri.choam.unsafe.RefSyntax.value]]
   */
  final def readRef[A](ref: Ref[A])(implicit ir: InRoRxn): A = {
    ir.readRef(ref.loc)
  }

  final def readRefArray[A](arr: Ref.Array[A], idx: Int)(implicit ir: InRoRxn): A = {
    ir.readRefArray(arr, idx)
  }

  /**
   * @see [[dev.tauri.choam.core.Ref.set]]
   * @see [[dev.tauri.choam.unsafe.RefSyntax.value_=]]
   */
  final def writeRef[A](ref: Ref[A], nv: A)(implicit ir: InRxn): Unit = {
    ir.writeRef(ref.loc, nv)
  }

  final def writeRefArray[A](arr: Ref.Array[A], idx: Int, nv: A)(implicit ir: InRxn): Unit = {
    ir.writeRefArray(arr, idx, nv)
  }

  /** @see [[dev.tauri.choam.core.Ref.update]] */
  final def updateRef[A](ref: Ref[A])(f: A => A)(implicit ir: InRxn): Unit = {
    ir.updateRef(ref.loc, f)
  }

  final def updateRefArray[A](arr: Ref.Array[A], idx: Int)(f: A => A)(implicit ir: InRxn): Unit = {
    ir.updateRefArray(arr, idx, f)
  }

  final def getAndSetRef[A](ref: Ref[A], nv: A)(implicit ir: InRxn): A = {
    ir.getAndSetRef(ref.loc, nv)
  }

  /** @see [[dev.tauri.choam.core.Rxn.postCommit]] */
  final def addPostCommit[A](pc: Rxn[Unit])(implicit ir: InRxn2): Unit = {
    ir.imperativePostCommit(pc)
  }

  /** @see [[dev.tauri.choam.core.Rxn.unsafe.tentativeRead]] */
  final def tentativeRead[A](ref: Ref[A])(implicit ir: InRoRxn): A = {
    ir.imperativeTentativeRead(ref.loc)
  }

  final def tentativeReadArray[A](arr: Ref.Array[A], idx: Int)(implicit ir: InRoRxn): A = {
    ir.imperativeTentativeReadArray(arr, idx)
  }

  /** @see [[dev.tauri.choam.core.Rxn.unsafe.ticketRead]] */
  final def ticketRead[A](ref: Ref[A])(implicit ir: InRoRxn): Ticket[A] = {
    ir.imperativeTicketRead(ref.loc)
  }

  final def ticketReadArray[A](arr: Ref.Array[A], idx: Int)(implicit ir: InRoRxn): Ticket[A] = {
    ir.imperativeTicketReadArray(arr, idx)
  }

  final def panic(ex: Throwable)(implicit ir: InRxn): Nothing = {
    Rxn.unsafe.imperativePanicImpl(ex)
  }

  private[choam] final def alwaysRetry()(implicit ir: InRxn): Nothing = {
    throw RetryException.notPermanentFailure
  }
}
