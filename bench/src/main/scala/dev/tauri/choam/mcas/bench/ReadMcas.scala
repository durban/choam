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
package mcas
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import _root_.dev.tauri.choam.bench.util.McasImplState

/**
 * Benchmark for reading with different MCAS implementations.
 *
 * One thread just reads 2 refs one-by-one, as fast as it can.
 * The other thread occasionally changes the values with a 2-CAS.
 */
@Fork(2)
class ReadMcas {

  import ReadMcas._

  @Benchmark
  @Group("ReadMcas")
  def read(s: RefSt, t: ThSt, bh: Blackhole): Unit = {
    bh.consume(t.mcasCtx.readDirect(s.ref1))
    bh.consume(t.mcasCtx.readDirect(s.ref2))
  }

  @Benchmark
  @Group("ReadMcas")
  def change(s: RefSt, t: ThSt): Unit = {
    val next1 = t.nextString()
    val next2 = t.nextString()
    val success = {
      val d0 = t.mcasCtx.start()
      val Some((_, d1)) = t.mcasCtx.readMaybeFromLog(s.ref1, d0) : @unchecked
      val d2 = d1.overwrite(d1.getOrElseNull(s.ref1).withNv(next1))
      val Some((_, d3)) = t.mcasCtx.readMaybeFromLog(s.ref2, d2) : @unchecked
      val d4 = d3.overwrite(d3.getOrElseNull(s.ref2).withNv(next2))
      t.mcasCtx.tryPerformOk(d4)
    }
    if (success) {
      // we only occasionally want to change values, so wait a bit:
      Blackhole.consumeCPU(ReadMcas.tokens)
    } else {
      throw new Exception
    }
  }
}

object ReadMcas {

  final val tokens = 4096L

  @State(Scope.Benchmark)
  class RefSt {
    val ref1: MemoryLocation[String] = Ref.unsafe("1").loc
    val ref2: MemoryLocation[String] = Ref.unsafe("2").loc
  }

  @State(Scope.Thread)
  class ThSt extends McasImplState
}
