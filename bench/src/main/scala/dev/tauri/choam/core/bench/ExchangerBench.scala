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

import profiler.RxnProfiler
import dev.tauri.choam.bench.util.{ McasImplState, McasImplStateBase }

@Fork(3)
@Threads(2) // 4, 6, 8, ...
class ExchangerBench {

  import ExchangerBench._

  @Benchmark
  @Group("exchanger")
  def exchangerLeft(s: St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.left("foo").unsafePerformInternal(ctx = k.mcasCtx))
  }

  @Benchmark
  @Group("exchanger")
  def exchangerRight(s: St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.right("bar").unsafePerformInternal(ctx = k.mcasCtx))
  }

  // @Benchmark
  // @Group("baseline")
  // def baseline(bh: Blackhole): Unit = {
  //   bh.consume(RxnProfiler.exchangeCounter.increment())
  // }
}

object ExchangerBench {

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {

    private[this] val exchanger: Exchanger[String, String] =
      RxnProfiler.profiledExchanger[String, String].unsafePerform(this.mcasImpl)

    final def left(s: String): Rxn[Option[String]] =
      exchanger.exchange(s).?

    final def right(s: String): Rxn[Option[String]] =
      exchanger.dual.exchange(s).?

    // Exchanger params:

    @Param(Array("64"))
    var minMaxMisses: Byte = 0

    @Param(Array("4"))
    var minMaxExchanges: Byte = 0

    @Param(Array("8"))
    var maxSizeShift: Byte = 0

    @Param(Array("1024"))
    var maxSpin: Int = 0

    @Param(Array("128"))
    var defaultSpin: Int = 0

    @Param(Array("16"))
    var maxSpinShift: Byte = 0

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
