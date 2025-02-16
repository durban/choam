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
package internal
package mcas
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import _root_.dev.tauri.choam.bench.util._

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class FailedCAS1Bench {

  import McasBenchHelpers._

  @Benchmark
  def failedCAS1(r: RefState, t: McasImplState, rnd: RandomState): Unit = {
    val succ = t.mcasCtx.tryPerformSingleCas(r.ref, ov = incorrectOv, nv = rnd.nextString())
    if (succ) throw new AssertionError("CAS should've failed")
  }

  @Benchmark
  def failedCAS1Reference(r: RefState, rnd: RandomState): Unit = {
    val succ = r.ref.unsafeCasV(incorrectOv, rnd.nextString())
    if (succ) throw new AssertionError("CAS should've failed")
  }
}

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class CAS1LoopBench {

  import McasBenchHelpers._

  @Benchmark
  def successfulCAS1Loop(r: RefState, t: McasImplState, rnd: RandomState): Unit = {
    val ref = r.ref
    val mcasCtx = t.mcasCtx
    @tailrec
    def go(): Unit = {
      val ov = mcasCtx.readDirect(ref)
      val nv = (ov.toLong + rnd.nextLong()).toString
      val succ = mcasCtx.tryPerformSingleCas(ref, ov, nv)
      if (succ) ()
      else go()
    }
    go()
  }

  @Benchmark
  def successfulCAS1LoopReference(r: RefState, rnd: RandomState): Unit = {
    val ref = r.ref
    @tailrec
    def go(): Unit = {
      val ov = ref.unsafeGetV()
      val nv = (ov.toLong + rnd.nextLong()).toString
      val succ = ref.unsafeCasV(ov, nv)
      if (succ) ()
      else go()
    }
    go()
  }
}

object McasBenchHelpers {

  final val incorrectOv = "no such number"

  @State(Scope.Benchmark)
  class RefState extends McasImplStateBase {
    val ref: MemoryLocation[String] =
      Ref.unsafePadded(ThreadLocalRandom.current().nextLong().toString, this.mcasImpl.currentContext().refIdGen).loc
  }
}
