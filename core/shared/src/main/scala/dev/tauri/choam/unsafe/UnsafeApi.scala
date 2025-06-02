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

import core.Rxn

object UnsafeApi {

  final def apply(rt: ChoamRuntime): UnsafeApi =
    new UnsafeApi(rt) {}

  private[choam] final def runBlock[A](state: InRxn, block: InRxn => A): A = {
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
    result
  }
}

sealed abstract class UnsafeApi private (rt: ChoamRuntime) {

  /**
   * Note: don't nest calls to `atomically`!
   *
   * Instead pass the `InRxn` argument implicitly
   * to methods called from the `block`.
   */
  final def atomically[A](block: InRxn => A): A = {
    val state = Rxn.unsafe.startImperative(this.rt.mcasImpl)
    state.initCtx()

    @tailrec
    def go(): A = {
      val result = UnsafeApi.runBlock(state, block)
      if (state.imperativeCommit()) {
        result
      } else {
        state.rollback()
        go()
      }
    }

    go()
  }
}
