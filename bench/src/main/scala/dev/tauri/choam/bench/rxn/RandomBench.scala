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
package bench
package rxn

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import cats.effect.std.{ Random, SecureRandom, UUIDGen }

import org.openjdk.jmh.annotations._

import core.Rxn
import util._

@Fork(3)
@Threads(2)
@BenchmarkMode(Array(Mode.Throughput))
class RandomBench {

  @Benchmark
  def baseline(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.baseline(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rndFast(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.rndFast.nextLongBounded(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rndDeterministic(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.rndDeterministic.nextLongBounded(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rndMinimal1(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.rndMinimal1.nextLongBounded(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rndMinimal2(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.rndMinimal2.nextLongBounded(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rndSecure(s: RandomBench.St, k: McasImplState, rnd: RandomState): Long = {
    s.rndSecure.nextLongBounded(s.bound(rnd)).unsafePerformInternal(k.mcasCtx)
  }

  @Benchmark
  def rxnUuidGen(s: RandomBench.St, k: McasImplState): UUID = {
    s.uuidGen.randomUUID.unsafePerformInternal(k.mcasCtx)
  }
}

object RandomBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {
    def bound(k: RandomState): Long =
      k.nextIntBounded(1024*1024*1024).toLong + 1L
    def baseline(n: Long): Rxn[Long] =
      Rxn.pure(n)
    val rndFast: Random[Rxn] =
      Rxn.fastRandom
    val rndDeterministic: Random[Rxn] =
      Rxn.deterministicRandom(ThreadLocalRandom.current().nextLong()).unsafePerform(McasImplStateBase.mcasImpl)
    val rndMinimal1: Random[Rxn] =
      internal.random.minimalRandom1(ThreadLocalRandom.current().nextLong()).unsafePerform(McasImplStateBase.mcasImpl)
    val rndMinimal2: Random[Rxn] =
      internal.random.minimalRandom2(ThreadLocalRandom.current().nextLong()).unsafePerform(McasImplStateBase.mcasImpl)
    val rndSecure: SecureRandom[Rxn] =
      Rxn.slowRandom
    val uuidGen: UUIDGen[Rxn] =
      Rxn.uuidGenForRxn
  }
}
