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
package mcas
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import _root_.dev.tauri.choam.bench.util._

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class FailedCAS1Bench {

  import KCASBenchHelpers._

  @Benchmark
  def failedCAS1(r: RefState, t: KCASImplState): Unit = {
    val succ = t.kcasCtx.tryPerformBool(
      t.kcasCtx.addCas(t.kcasCtx.start(), r.ref, incorrectOv, t.nextString())
    )
    if (succ) throw new AssertionError("CAS should've failed")
  }

  @Benchmark
  def failedCAS1Reference(r: RefState, t: KCASImplState): Unit = {
    val succ = r.ref.unsafeCasVolatile(incorrectOv, t.nextString())
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
    val kcasCtx = t.kcasCtx
    @tailrec
    def go(): Unit = {
      val ov = kcasCtx.read(ref)
      val nv = (ov.toLong + t.nextLong()).toString
      val succ = kcasCtx.tryPerformBool(
        kcasCtx.addCas(kcasCtx.start(), ref, ov, nv)
      )
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
      val ov = ref.unsafeGetVolatile()
      val nv = (ov.toLong + t.nextLong()).toString
      val succ = ref.unsafeCasVolatile(ov, nv)
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
    val ref: MemoryLocation[String] =
      Ref.unsafe(ThreadLocalRandom.current().nextLong().toString).loc
  }
}
