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
package core
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import internal.mcas.Mcas
import profiler.RxnProfiler
import dev.tauri.choam.bench.util.McasImplState

@Fork(3)
@Threads(2) // 4, 6, 8, ...
class ExchangerBench {

  import ExchangerBench._

  @Benchmark
  @Group("exchanger")
  def exchangerLeft(s: St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.left.unsafePerformInternal("foo", ctx = k.mcasCtx))
  }

  @Benchmark
  @Group("exchanger")
  def exchangerRight(s: St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.right.unsafePerformInternal("bar", ctx = k.mcasCtx))
  }

  // @Benchmark
  // @Group("baseline")
  // def baseline(bh: Blackhole): Unit = {
  //   bh.consume(RxnProfiler.exchangeCounter.increment())
  // }
}

object ExchangerBench {

  @State(Scope.Benchmark)
  class St {

    private[this] val exchanger: Exchanger[String, String] =
      RxnProfiler.profiledExchanger[String, String].unsafePerform((), Mcas.NullMcas)

    val left: Rxn[String, Option[String]] =
      exchanger.exchange.?

    val right: Rxn[String, Option[String]] =
      exchanger.dual.exchange.?

    // Exchanger params:

    @Param(Array("64"))
    var minMaxMisses: Byte = _

    @Param(Array("4"))
    var minMaxExchanges: Byte = _

    @Param(Array("8"))
    var maxSizeShift: Byte = _

    @Param(Array("1024"))
    var maxSpin: Int = _

    @Param(Array("128"))
    var defaultSpin: Int = _

    @Param(Array("16"))
    var maxSpinShift: Byte = _

    @Setup
    def setup(): Unit = {
      val p = Exchanger.Params(
        maxMisses = this.minMaxMisses,
        minMisses = (-this.minMaxMisses).toByte,
        maxExchanges = this.minMaxExchanges,
        minExchanges = (-this.minMaxExchanges).toByte,
        maxSizeShift = this.maxSizeShift,
        maxSpin = this.maxSpin,
        defaultSpin = this.defaultSpin,
        maxSpinShift = this.maxSpinShift,
      )
      Exchanger.params = p // volatile write
    }
  }
}
