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
package skiplistbench

import java.lang.Long.MAX_VALUE
import java.util.concurrent.{ ConcurrentSkipListMap, ThreadLocalRandom }

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import internal.skiplist.SkipListMap

@Fork(2)
@Threads(2)
class SkipListBench {

  import SkipListBench._

  @Benchmark
  def insertRemoveJucConcurrentSkipListMap(s: CslmState, bh: Blackhole): Unit = {
    val k = s.randomNewKey(ThreadLocalRandom.current())
    val m = s.cslm
    bh.consume(m.put(k, "FOO"))
    bh.consume(m.remove(k))
  }

  @Benchmark
  def insertRemoveSkipListMap(s: SlmState, bh: Blackhole): Unit = {
    val k = s.randomNewKey(ThreadLocalRandom.current())
    val m = s.slm
    bh.consume(m.put(k, "FOO"))
    bh.consume(m.del(k))
  }
}

object SkipListBench {

  final val size = 1024 * 1024

  final val newKeysSize = 1024

  final val foo = "FOO"

  @State(Scope.Benchmark)
  sealed abstract class AbstractState {

    protected[this] def newKeys: Array[Long]

    protected[this] final def nextKey(tlr: ThreadLocalRandom): Long = {
      val n = tlr.nextLong(MAX_VALUE >> 2)
      java.lang.Math.abs(n)
    }

    final def randomNewKey(tlr: ThreadLocalRandom): Long = {
      val idx = tlr.nextInt(newKeysSize)
      newKeys(idx)
    }
  }

  @State(Scope.Benchmark)
  class CslmState extends AbstractState {

    val cslm: ConcurrentSkipListMap[Long, String] = {
      val m = new ConcurrentSkipListMap[Long, String]
      val tlr = ThreadLocalRandom.current()
      for (_ <- 0 until size) {
        m.put(nextKey(tlr), foo)
      }
      while (m.size() < size) {
        m.put(nextKey(tlr), foo)
      }
      assert(m.size() == size)
      m
    }

    private[this] val _newKeys: Array[Long] = {
      val tlr = ThreadLocalRandom.current()
      val arr = new Array[Long](newKeysSize)
      var idx = 0
      while (idx < newKeysSize) {
        var newKey = nextKey(tlr)
        while (cslm.containsKey(newKey)) {
          newKey = nextKey(tlr)
        }
        arr(idx) = newKey
        idx += 1
      }
      arr
    }

    protected[this] final override def newKeys: Array[Long] = {
      _newKeys
    }
  }

  @State(Scope.Benchmark)
  class SlmState extends AbstractState {

    val slm: SkipListMap[Long, String] = {
      val m = new SkipListMap[Long, String]
      val tlr = ThreadLocalRandom.current()
      for (_ <- 0 until size) {
        m.put(key = nextKey(tlr), value = foo)
      }
      while (m.size < size) {
        m.put(key = nextKey(tlr), value = foo) : Unit
      }
      assert(m.size == size)
      m
    }

    private[this] val _newKeys: Array[Long] = {
      val tlr = ThreadLocalRandom.current()
      val arr = new Array[Long](newKeysSize)
      var idx = 0
      while (idx < newKeysSize) {
        var newKey = nextKey(tlr)
        while (slm.contains(newKey)) {
          newKey = nextKey(tlr)
        }
        arr(idx) = newKey
        idx += 1
      }
      arr
    }

    protected[this] final override def newKeys: Array[Long] = {
      _newKeys
    }
  }
}
