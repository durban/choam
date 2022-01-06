/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.std.Random

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

@Fork(2)
@Threads(2)
@BenchmarkMode(Array(Mode.AverageTime))
class RandomBench {

  @Benchmark
  def threadLocalContext(s: RandomBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(
      s.rndThreadLocalContext.nextInt.unsafePerformInternal(null, k.kcasCtx)
    )
  }

  @Benchmark
  def threadLocalContextBetweenInt(s: RandomBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(
      s.rndThreadLocalContext.betweenInt(0, 8388608).unsafePerformInternal(null, k.kcasCtx)
    )
  }

  @Benchmark
  def threadLocalContextBetweenIntCached(s: RandomBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(
      s.rndThreadLocalContextCached.betweenInt(0, 8388608).unsafePerformInternal(null, k.kcasCtx)
    )
  }

  // @Benchmark
  // def secureRandom(s: RandomBench.St, bh: Blackhole, k: KCASImplState): Unit = {
  //   bh.consume(
  //     s.rndSecure.nextInt.unsafePerformInternal(null, k.kcasCtx)
  //   )
  // }

  @Benchmark
  def baseline(s: RandomBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    bh.consume(
      s.baseline.unsafePerformInternal(null, k.kcasCtx)
    )
  }
}

object RandomBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {
    val baseline: Axn[Int] =
      Rxn.pure(42)
    val rndThreadLocalContext: Random[Axn] =
      Rxn.fastRandom.unsafeRun(mcas.MCAS.EMCAS)
    val rndThreadLocalContextCached: Random[Axn] =
      Rxn.fastRandomCached.unsafeRun(mcas.MCAS.EMCAS)
    val rndSecure: Random[Axn] =
      Rxn.secureRandom.unsafeRun(mcas.MCAS.EMCAS)
  }
}
