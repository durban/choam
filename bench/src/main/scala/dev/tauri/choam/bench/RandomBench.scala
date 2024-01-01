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
package bench

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import cats.effect.std.{ Random, SecureRandom, UUIDGen }

import org.openjdk.jmh.annotations._

import internal.mcas.Mcas
import util._

@Fork(3)
@Threads(2)
@BenchmarkMode(Array(Mode.Throughput))
class RandomBench {

  @Benchmark
  def baseline(s: RandomBench.St, k: McasImplState): Long = {
    s.baseline(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def rndThreadLocal(s: RandomBench.St, k: McasImplState): Long = {
    s.rndThreadLocal.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def rndDeterministic(s: RandomBench.St, k: McasImplState): Long = {
    s.rndDeterministic.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def rndMinimal1(s: RandomBench.St, k: McasImplState): Long = {
    s.rndMinimal1.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def rndMinimal2(s: RandomBench.St, k: McasImplState): Long = {
    s.rndMinimal2.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def rndSecureRxn(s: RandomBench.St, k: McasImplState): Long = {
    s.rndSecureRxn.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  @deprecated("so that we can call secureRandomWrapper", since = "0.4")
  def rndSecureWrapper(s: RandomBench.St, k: McasImplState): Long = {
    s.rndSecureWrapper.nextLongBounded(s.bound(k)).unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  def uuidRxn(s: RandomBench.St, k: McasImplState): UUID = {
    s.uuidRxn.randomUUID.unsafePerformInternal(null, k.mcasCtx)
  }

  @Benchmark
  @deprecated("so that we can call uuidGenWrapper", since = "0.4")
  def uuidWrapper(s: RandomBench.St, k: McasImplState): UUID = {
    s.uuidWrapper.randomUUID.unsafePerformInternal(null, k.mcasCtx)
  }
}

object RandomBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St {
    def bound(k: RandomState): Long =
      k.nextIntBounded(1024*1024*1024).toLong + 1L
    def baseline(n: Long): Axn[Long] =
      Rxn.pure(n)
    val rndThreadLocal: Random[Axn] =
      Rxn.fastRandom
    val rndDeterministic: Random[Axn] =
      Rxn.deterministicRandom(ThreadLocalRandom.current().nextLong()).unsafeRun(Mcas.NullMcas)
    val rndMinimal1: Random[Axn] =
      random.minimalRandom1(ThreadLocalRandom.current().nextLong()).unsafeRun(Mcas.NullMcas)
    val rndMinimal2: Random[Axn] =
      random.minimalRandom2(ThreadLocalRandom.current().nextLong()).unsafeRun(Mcas.NullMcas)
    val rndSecureRxn: SecureRandom[Axn] =
      Rxn.secureRandom
    @deprecated("so that we can call secureRandomWrapper", since = "0.4")
    val rndSecureWrapper: SecureRandom[Axn] =
      random.secureRandomWrapper.unsafeRun(Mcas.NullMcas)
    val uuidRxn: UUIDGen[Axn] =
      Rxn.uuidGenInstance
    @deprecated("so that we can call uuidGenWrapper", since = "0.4")
    val uuidWrapper: UUIDGen[Axn] =
      Rxn.uuidGenWrapper
  }
}
