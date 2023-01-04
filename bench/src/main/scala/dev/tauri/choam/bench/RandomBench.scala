/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import cats.effect.std.Random

import org.openjdk.jmh.annotations._

import util._

@Fork(3)
@Threads(2)
@BenchmarkMode(Array(Mode.AverageTime))
class RandomBench {

  @Benchmark
  def baseline(s: RandomBench.St, k: KCASImplState): Int = {
    s.baseline(s.bound(k)).unsafePerformInternal(null, k.kcasCtx)
  }

  @Benchmark
  def betweenIntThreadLocal(s: RandomBench.St, k: KCASImplState): Int = {
    s.rndThreadLocal.betweenInt(0, s.bound(k)).unsafePerformInternal(null, k.kcasCtx)
  }

  @Benchmark
  def betweenIntDeterministic(s: RandomBench.St, k: KCASImplState): Int = {
    s.rndDeterministic.betweenInt(0, s.bound(k)).unsafePerformInternal(null, k.kcasCtx)
  }

  @Benchmark
  def betweenIntMinimal(s: RandomBench.St, k: KCASImplState): Int = {
    s.rndMinimal.betweenInt(0, s.bound(k)).unsafePerformInternal(null, k.kcasCtx)
  }

  @Benchmark
  def betweenIntSecure(s: RandomBench.St, k: KCASImplState): Int = {
    s.rndSecure.betweenInt(0, s.bound(k)).unsafePerformInternal(null, k.kcasCtx)
  }
}

object RandomBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {
    def bound(k: RandomState): Int =
      k.nextIntBounded(1024*1024) + 1
    def baseline(i: Int): Axn[Int] =
      Rxn.pure(i)
    val rndThreadLocal: Random[Axn] =
      Rxn.fastRandom.unsafeRun(mcas.Mcas.NullMcas)
    val rndDeterministic: Random[Axn] =
      Rxn.deterministicRandom(ThreadLocalRandom.current().nextLong()).unsafeRun(mcas.Mcas.NullMcas)
    val rndMinimal: Random[Axn] =
      Rxn.minimalRandom(ThreadLocalRandom.current().nextLong()).unsafeRun(mcas.Mcas.NullMcas)
    val rndSecure: Random[Axn] =
      Rxn.secureRandom.unsafeRun(mcas.Mcas.NullMcas)
  }
}
