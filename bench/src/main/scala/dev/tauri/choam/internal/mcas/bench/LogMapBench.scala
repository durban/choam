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
package internal
package mcas
package bench

import scala.collection.immutable.TreeMap

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import internal.mcas.{ MemoryLocation, LogEntry, Version }
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
  def insertHamt(s: HamtState, rnd: RandomState, bh: Blackhole): Unit = {
    val idx = rnd.nextIntBounded(DummySize)
    val newKey = s.dummyKeys(idx)
    bh.consume(newKey)
    val newHwd = s.dummyHwds(idx)
    bh.consume(s.map.inserted(newHwd.cast[Any]))
  }

  @Benchmark
  def insertLog(s: LogMapState, rnd: RandomState, bh: Blackhole): Unit = {
    val idx = rnd.nextIntBounded(DummySize)
    val newKey = s.dummyKeys(idx)
    bh.consume(newKey)
    val newHwd = s.dummyHwds(idx)
    bh.consume(s.map.inserted(newHwd))
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
  def lookupHamt(s: HamtState, rnd: RandomState, bh: Blackhole): Unit = {
    val isDummy = (s.size == 0) || ((rnd.nextInt() % 2) == 0)
    val key = if (!isDummy) {
      val idx = rnd.nextIntBounded(s.size)
      s.keys(idx)
    } else {
      val idx = rnd.nextIntBounded(DummySize)
      s.dummyKeys(idx)
    }
    bh.consume(s.map.getOrElseNull(key.id))
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
  def overwriteHamt(s: HamtState, rnd: RandomState, bh: Blackhole): Unit = {
    if (s.size == 0) {
      bh.consume(0)
    } else {
      val idx = rnd.nextIntBounded(s.size)
      val key = s.keys(idx)
      bh.consume(key)
      val newHwd = s.newHwds(idx)
      bh.consume(s.map.updated(newHwd.cast[Any]))
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
      bh.consume(s.map.updated(newHwd))
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

  @Benchmark
  def toArrayBaseline(s: BaselineState): Array[WdLike[Any]] = {
    new Array[WdLike[Any]](s.size)
  }

  @Benchmark
  def toArrayHamt(s: HamtState): Array[WdLike[Any]] = {
    s.map.toArray(null, flag = false)
  }

  @Benchmark
  def toArrayLog(s: LogMapState): Array[WdLike[Any]] = {
    val map = s.map
    val arr = new Array[WdLike[Any]](map.size)
    val it = map.valuesIterator
    var idx = 0
    while (it.hasNext) {
      val wd = it.next().cast[Any]
      arr(idx) = if (wd.readOnly) wd else new emcas.EmcasWordDesc(wd, null)
      idx += 1
    }
    arr
  }

  @Benchmark
  def toArrayTree(s: TreeMapState): Array[WdLike[Any]] = {
    val map = s.map
    val arr = new Array[WdLike[Any]](map.size)
    val it = map.valuesIterator
    var idx = 0
    while (it.hasNext) {
      val wd = it.next()
      arr(idx) = if (wd.readOnly) wd else new emcas.EmcasWordDesc(wd, null)
      idx += 1
    }
    arr
  }
}

object LogMapBench {

  final val DummySize = 8

  @State(Scope.Thread)
  abstract class BaseState {

    @Param(Array("0", "1", "2", "3", "8", "1024"))
    var size: Int =
      0

    var keys: Array[MemoryLocation[String]] =
      null

    var newHwds: Array[LogEntry[String]] =
      null

    var dummyKeys: Array[MemoryLocation[String]] =
      null

    var dummyHwds: Array[LogEntry[String]] =
      null

    def baseSetup(): Unit = {
      this.keys = new Array(this.size)
      this.newHwds = new Array(this.size)
      for (idx <- 0 until this.size) {
        val ref = MemoryLocation.unsafe("a")
        this.keys(idx) = ref
        this.newHwds(idx) = LogEntry.apply(ref, "a", "c", 0L)
      }
      this.dummyKeys = new Array(DummySize)
      this.dummyHwds = new Array(DummySize)
      for (idx <- 0 until DummySize) {
        val ref = MemoryLocation.unsafe("x")
        this.dummyKeys(idx) = ref
        this.dummyHwds(idx) = LogEntry.apply(ref, "x", "y", 0L)
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
        this.map = this.map.inserted(
          LogEntry.apply(ref, "a", "b", Version.Start)
        )
      }
    }
  }

  @State(Scope.Thread)
  private[mcas] class HamtState extends BaseState {

    var map: LogMap2[Any] =
      LogMap2.empty

    @Setup
    def setup(): Unit = {
      this.baseSetup()
      for (ref <- this.keys) {
        this.map = this.map.inserted(
          LogEntry.apply(ref.cast[Any], "a", "b", Version.Start)
        )
      }
    }
  }

  @State(Scope.Thread)
  class TreeMapState extends BaseState {

    var map: TreeMap[MemoryLocation[Any], LogEntry[Any]] =
      TreeMap.empty(MemoryLocation.orderingInstance[Any])

    @Setup
    def setup(): Unit = {
      this.baseSetup()
      for (ref <- this.keys) {
        this.map = this.map.updated(
          ref.asInstanceOf[MemoryLocation[Any]],
          LogEntry.apply(ref.cast[Any], "a", "b", Version.Start),
        )
      }
    }
  }
}
