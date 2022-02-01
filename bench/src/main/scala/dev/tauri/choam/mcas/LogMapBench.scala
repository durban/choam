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

import scala.collection.immutable.TreeMap

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import dev.tauri.choam.bench.util.RandomState

@Fork(1)
@Threads(1)
private[mcas] class LogMapBench {

  import LogMapBench._

  @Benchmark
  def lookupBaseline(s: BaselineState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (s.size == 0) || ((rnd.nextInt() % 2) == 0)
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(key)
  }

  @Benchmark
  def lookupLog(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (s.size == 0) || ((rnd.nextInt() % 2) == 0)
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(s.map.getOrElse(key, null))
  }

  @Benchmark
  def lookupTree(s: TreeMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (s.size == 0) || ((rnd.nextInt() % 2) == 0)
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(s.map.getOrElse(key.asInstanceOf[MemoryLocation[Any]], null))
  }
}

object LogMapBench {

  final val DummySize = 8

  @State(Scope.Thread)
  abstract class BaseState {

    @Param(Array("0")) // , "1", "2", "4", "8", "16"))
    var size: Int =
      _

    var keys: Array[MemoryLocation[String]] =
      _

    var dummyKeys: Array[MemoryLocation[String]] =
      _

    def baseSetup(): Unit = {
      this.keys = new Array(this.size)
      for (idx <- 0 until this.size) {
        this.keys(idx) = MemoryLocation.unsafe("a")
      }
      this.dummyKeys = new Array(DummySize)
      for (idx <- 0 until DummySize) {
        this.dummyKeys(idx) = MemoryLocation.unsafe("x")
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

  @State(Scope.Thread)
  class TreeMapState extends BaseState {

    var map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]] =
      TreeMap.empty(MemoryLocation.orderingInstance[Any])

    @Setup
    def setup(): Unit = {
      this.baseSetup()
      for (ref <- this.keys) {
        this.map = this.map.updated(
          ref.asInstanceOf[MemoryLocation[Any]],
          HalfWordDescriptor(ref, "a", "b", version = Version.Start).cast[Any],
        )
      }
    }
  }
}
