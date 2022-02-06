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
  def insertBaseline(s: BaselineState, rnd: RandomState, bh: Blackhole): Unit = {
    val idx = rnd.nextIntBounded(DummySize)
    val newKey = s.dummyKeys(idx)
    bh.consume(newKey)
    val newHwd = s.dummyHwds(idx)
    bh.consume(newHwd)
  }

  @Benchmark
  def insertLog(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val idx = rnd.nextIntBounded(DummySize)
    val newKey = s.dummyKeys(idx)
    bh.consume(newKey)
    val newHwd = s.dummyHwds(idx)
    bh.consume(s.map.updated(newKey, newHwd))
  }

  @Benchmark
  def insertTree(s: TreeMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val idx = rnd.nextIntBounded(DummySize)
    val newKey = s.dummyKeys(idx)
    bh.consume(newKey)
    val newHwd = s.dummyHwds(idx)
    bh.consume(s.map.updated(newKey.cast[Any], newHwd))
  }

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
    bh.consume(s.map.getOrElse(key.cast[Any], null))
  }

  @Benchmark
  def overwriteBaseline(s: BaselineState, rnd: RandomState, bh: Blackhole): Unit = {
    if (s.size == 0) {
      bh.consume(0)
    } else {
      val idx = rnd.nextIntBounded(s.size)
      val key = s.keys(idx)
      bh.consume(key)
      val newHwd = s.newHwds(idx)
      bh.consume(newHwd)
    }
  }

  @Benchmark
  def overwriteLog(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
    if (s.size == 0) {
      bh.consume(0)
    } else {
      val idx = rnd.nextIntBounded(s.size)
      val key = s.keys(idx)
      bh.consume(key)
      val newHwd = s.newHwds(idx)
      bh.consume(s.map.updated(key, newHwd))
    }
  }

  @Benchmark
  def overwriteTree(s: TreeMapState, rnd: RandomState, bh: Blackhole): Unit = {
    if (s.size == 0) {
      bh.consume(0)
    } else {
      val idx = rnd.nextIntBounded(s.size)
      val key = s.keys(idx)
      bh.consume(key)
      val newHwd = s.newHwds(idx)
      bh.consume(s.map.updated(key.cast[Any], newHwd))
    }
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

    var newHwds: Array[HalfWordDescriptor[String]] =
      _

    var dummyKeys: Array[MemoryLocation[String]] =
      _

    var dummyHwds: Array[HalfWordDescriptor[String]] =
      _

    def baseSetup(): Unit = {
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

  @State(Scope.Thread)
  class TreeMapState extends BaseState {

    var map: TreeMap[MemoryLocation[Any], HalfWordDescriptor[Any]] =
      TreeMap.empty(MemoryLocation.orderingInstance[Any])

    @Setup
    def setup(): Unit = {
      this.baseSetup()
      for (ref <- this.keys) {
        this.map = this.map.updated(
          ref.cast[Any],
          HalfWordDescriptor(ref, "a", "b", version = Version.Start).cast[Any],
        )
      }
    }
  }
}
