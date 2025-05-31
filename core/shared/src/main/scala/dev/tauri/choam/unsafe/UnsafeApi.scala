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

import scala.language.implicitConversions

import core.{ Rxn, Ref }
import internal.mcas.Mcas

abstract class UnsafeApi(rt: ChoamRuntime) {

  private[this] val _fallback = new MaybeInRxn.UnsealedMaybeInRxn {
    private[choam] final override def currentContext(): Mcas.ThreadContext =
      rt.mcasImpl.currentContext()
  }

  implicit final def maybeInRxnFallback: MaybeInRxn =
    _fallback

  implicit final def RefSyntax[A](ref: Ref[A]): RefSyntax[A] =
    new RefSyntax[A](ref)

  final def unsafeRuntime: ChoamRuntime =
    this.rt

  final def atomically[A](block: InRxn => A): A = {
    val state = Rxn.unsafe.startImperative(this.rt.mcasImpl)
    state.initCtx()

    @tailrec
    def go(): A = {
      var done = false
      var result: A = nullOf[A]
      while (!done) {
        try {
          result = block(state)
          done = true
        } catch {
          case _: RetryException =>
            state.rollback()
        }
      }
      if (state.imperativeCommit()) {
        result
      } else {
        state.rollback()
        go() // retry
      }
    }

    go()
  }

  final def newRef[A](
    initial: A,
    strategy: Ref.AllocationStrategy = Ref.AllocationStrategy.Default,
  )(implicit mir: MaybeInRxn): Ref[A] = {
    Ref.unsafe(initial, strategy, mir.currentContext().refIdGen)
  }

  final def readRef[A](ref: Ref[A])(implicit ir: InRxn): A = {
    ir.readRef(ref.loc)
  }

  final def writeRef[A](ref: Ref[A], nv: A)(implicit ir: InRxn): Unit = {
    ir.writeRef(ref.loc, nv)
  }

  final def updateRef[A](ref: Ref[A])(f: A => A)(implicit ir: InRxn): Unit = {
    // TODO: optimize:
    val ov = readRef(ref)
    val nv = f(ov)
    writeRef(ref, nv)
  }

  final def tentativeRead[A](ref: Ref[A])(implicit ir: InRxn): A = {
    ir.imperativeTentativeRead(ref.loc)
  }

  final def ticketRead[A](ref: Ref[A])(implicit ir: InRxn): Ticket[A] = {
    ir.imperativeTicketRead(ref.loc)
  }

  final def newRefArray[A](
    size: Int,
    initial: A,
    strategy: Ref.Array.AllocationStrategy = Ref.Array.AllocationStrategy.Default,
  )(implicit mir: MaybeInRxn): Ref.Array[A] = {
    Ref.unsafeArray(size, initial, strategy, mir.currentContext().refIdGen)
  }
}
