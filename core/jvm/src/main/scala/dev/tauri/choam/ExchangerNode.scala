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

import mcas.MCAS
import Exchanger.{ Msg, NodeResult }

private final class ExchangerNode[C](val msg: Msg) {

  /**
   *     .---> result: FinishedEx[C] (fulfiller successfully completed)
   *    /
   * null (TODO: use a sentinel)
   *    \
   *     ˙---> Rescinded[C] (owner couldn't wait any more for the fulfiller)
   */

  val hole: Ref[NodeResult[C]] =
    Ref.unsafe(null)

  def spinWait(stats: ExchangerImplJvm.Statistics, ctx: MCAS.ThreadContext): Option[NodeResult[C]] = {
    @tailrec
    def go(n: Int): Option[NodeResult[C]] = {
      if (n > 0) {
        Backoff.once()
        val res = ctx.read(this.hole.loc)
        if (isNull(res)) {
          go(n - 1)
        } else {
          Some(res)
        }
      } else {
        None
      }
    }
    val maxSpin = Math.min(
      ExchangerImplJvm.Statistics.defaultSpin << stats.spinShift.toInt,
      ExchangerImplJvm.Statistics.maxSpin
    )
    val spin = 1 + ctx.random.nextInt(maxSpin)
    // println(s"spin waiting ${spin} (max. ${maxSpin}) - thread#${Thread.currentThread().getId()}")
    go(spin)
  }
}
