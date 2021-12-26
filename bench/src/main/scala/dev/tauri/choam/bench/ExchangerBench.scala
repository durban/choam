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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util.{ RxnProfiler, KCASImplState }

@Fork(3)
@Threads(2) // 4, 6, 8, ...
class ExchangerBench {

  import ExchangerBench._

  @Benchmark
  @Group("exchanger")
  def exchangerLeft(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(s.left.unsafePerformInternal("foo", ctx = k.kcasCtx))
  }

  @Benchmark
  @Group("exchanger")
  def exchangerRight(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(s.right.unsafePerformInternal("bar", ctx = k.kcasCtx))
  }

  @Benchmark
  @Group("baseline")
  def baseline(bh: Blackhole): Unit = {
    bh.consume(RxnProfiler.exchangeCounter.increment())
  }
}

object ExchangerBench {

  @State(Scope.Benchmark)
  class St {

    private[this] val exchanger: Exchanger[String, String] =
      Rxn.unsafe.exchanger[String, String].unsafePerform((), mcas.MCAS.EMCAS)

    // Every exchange has 2 sides, so only
    // the left side increments the counter:
    val left: Rxn[String, Option[String]] = {
      (exchanger.exchange >>> Rxn.unsafe.delay { r =>
        RxnProfiler.exchangeCounter.increment()
        r
      }).?
    }

    val right: Rxn[String, Option[String]] = {
      exchanger.dual.exchange.?
    }
  }
}
