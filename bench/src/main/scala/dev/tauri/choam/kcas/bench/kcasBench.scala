/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import dev.tauri.choam.bench.util._

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class FailedCAS1Bench {

  import KCASBenchHelpers._

  @Benchmark
  def failedCAS1(r: RefState, t: KCASImplState): Unit = {
    val succ = t.kcasImpl.start().withCAS(r.ref, incorrectOv, t.nextString()).tryPerform()
    if (succ) throw new AssertionError("CAS should've failed")
  }

  @Benchmark
  def failedCAS1Reference(r: RefState, t: KCASImplState): Unit = {
    val succ = r.ref.unsafeTryPerformCas(incorrectOv, t.nextString())
    if (succ) throw new AssertionError("CAS should've failed")
  }
}

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class CAS1LoopBench {

  import KCASBenchHelpers._

  @Benchmark
  def successfulCAS1Loop(r: RefState, t: KCASImplState): Unit = {
    val ref = r.ref
    val kcasImpl = t.kcasImpl
    @tailrec
    def go(): Unit = {
      val ov = kcasImpl.read(ref)
      val nv = (ov.toLong + t.nextLong()).toString
      val succ = kcasImpl.start().withCAS(ref, ov, nv).tryPerform()
      if (succ) ()
      else go()
    }
    go()
  }

  @Benchmark
  def successfulCAS1LoopReference(r: RefState, t: KCASImplState): Unit = {
    val ref = r.ref
    @tailrec
    def go(): Unit = {
      val ov = ref.unsafeTryRead()
      val nv = (ov.toLong + t.nextLong()).toString
      val succ = ref.unsafeTryPerformCas(ov, nv)
      if (succ) ()
      else go()
    }
    go()
  }
}

object KCASBenchHelpers {

  final val incorrectOv = "no such number"

  @State(Scope.Benchmark)
  class RefState {
    val ref = kcas.Ref.mk(ThreadLocalRandom.current().nextLong().toString)
  }
}
