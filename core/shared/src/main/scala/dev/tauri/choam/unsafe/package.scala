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

  private[this] val rt: ChoamRuntime =
    ChoamRuntime.unsafeBlocking()

  private[unsafe] final def runtime: ChoamRuntime =
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
}
