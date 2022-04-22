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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import dev.tauri.choam.bench.util.RandomState

@Fork(1)
@Threads(1)
private[mcas] class BloomFilterBench {

  import BloomFilterBench._

  @Benchmark
  def baseline(s: BaselineState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (rnd.nextInt() % 2) == 0
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(key)
  }

  // @Benchmark
  // def contains(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
  //   val isDummy = (rnd.nextInt() % 2) == 0
  //   val key = if (!isDummy) {
  //     val idx = rnd.nextIntBounded(s.size)
  //     s.keys(idx)
  //   } else {
  //     val idx = rnd.nextIntBounded(DummySize)
  //     s.dummyKeys(idx)
  //   }
  //   bh.consume(s.map.containsUnopt(key))
  // }

  @Benchmark
  def containsOpt(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (rnd.nextInt() % 2) == 0
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(s.map.contains(key))
  }
}

object BloomFilterBench {

  final val DummySize = 8

  @State(Scope.Thread)
  abstract class BaseState {

    @Param(Array("2", "4", "8"))
    var size: Int =
      0

    var keys: Array[MemoryLocation[String]] =
      null

    var newHwds: Array[HalfWordDescriptor[String]] =
      null

    var dummyKeys: Array[MemoryLocation[String]] =
      null

    var dummyHwds: Array[HalfWordDescriptor[String]] =
      null

    def baseSetup(): Unit = {
      assert(this.size != 0)
      this.keys = new Array(this.size)
      this.newHwds = new Array(this.size)
      for (idx <- 0 until this.size) {
        val ref = MemoryLocation.unsafe("a")
        this.keys(idx) = ref
        this.newHwds(idx) = HalfWordDescriptor(ref, "a", "c", version = 0L)
      }
      this.dummyKeys = new Array(DummySize)
      this.dummyHwds = new Array(DummySize)
      for (idx <- 0 until DummySize) {
        val ref = MemoryLocation.unsafe("x")
        this.dummyKeys(idx) = ref
        this.dummyHwds(idx) = HalfWordDescriptor(ref, "x", "y", version = 0L)
      }
    }
  }

  @State(Scope.Thread)
  class BaselineState extends BaseState {
    @Setup
    def setup(): Unit = {
      this.baseSetup()
    }
  }

  @State(Scope.Thread)
  private[mcas] class LogMapState extends BaseState {

    var map: LogMap =
      LogMap.empty

    @Setup
    def setup(): Unit = {
      this.baseSetup()
      for (ref <- this.keys) {
        this.map = this.map.updated(
          ref,
          HalfWordDescriptor(ref, "a", "b", version = Version.Start),
        )
      }
    }
  }
}
