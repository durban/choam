/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import internal.mcas.Mcas
import Exchanger.{ Msg, NodeResult }

private final class ExchangerNode[C](val msg: Msg) {

  /**
   *     .---> result: FinishedEx[C] (fulfiller successfully completed)
   *    /
   * null
   *    \
   *     ˙---> Rescinded[C] (owner couldn't wait any more for the fulfiller)
   */

  val hole: Ref[NodeResult[C]] =
    Ref.unsafePadded(null)

  // TODO: Also consider using `Thread.yield` and then
  // TODO: `LockSupport.parkNanos` after spinning (see
  // TODO: `java.util.concurrent.Exchanger`).

  def spinWait(
    stats: ExchangerImplJvm.Statistics,
    params: Exchanger.Params,
    ctx: Mcas.ThreadContext,
  ): Option[NodeResult[C]] = {
    @tailrec
    def go(n: Int): Option[NodeResult[C]] = {
      if (n > 0) {
        Backoff2.once()
        val res = ctx.readDirect(this.hole.loc)
        if (isNull(res)) {
          go(n - 1)
        } else {
          Some(res)
        }
      } else {
        None
      }
    }
    val spinShift = ExchangerImplJvm.Statistics.spinShift(stats).toInt
    val maxSpin = Math.min(
      params.defaultSpin << spinShift,
      params.maxSpin
    )
    val spin = 1 + ctx.random.nextInt(maxSpin)
    // println(s"spin waiting ${spin} (max. ${maxSpin}) - thread#${Thread.currentThread().getId()}")
    go(spin)
  }
}
