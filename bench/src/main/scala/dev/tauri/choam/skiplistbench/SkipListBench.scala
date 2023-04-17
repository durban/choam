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
package skiplistbench

import java.lang.Long.{ MAX_VALUE, MIN_VALUE }
import java.util.concurrent.{ ConcurrentSkipListMap, ThreadLocalRandom }

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import skiplist.SkipListMap

@Fork(2)
@Threads(2)
class SkipListBench {

  import SkipListBench._

  @Benchmark
  def insertRemoveJucConcurrentSkipListMap(s: CslmState, bh: Blackhole): Unit = {
    val k = s.nextDelay(ThreadLocalRandom.current())
    bh.consume(s.cslm.put(k, s.dummyCallback))
    bh.consume(s.cslm.remove(k))
  }

  @Benchmark
  def insertRemoveTimerSkipList(s: TslState, @unused bh: Blackhole): Unit = {
    val d = s.nextDelay(ThreadLocalRandom.current())
    val key = MIN_VALUE + d
    bh.consume(s.tsl.put(key, s.dummyCallback))
    bh.consume(s.tsl.del(key))
  }
}

object SkipListBench {

  type Callback = Right[Nothing, Unit] => Unit

  final val size = 1024 * 1024

  @State(Scope.Benchmark)
  sealed abstract class AbstractState {

    val dummyCallback: Callback =
      { _ => () }

    final def nextDelay(tlr: ThreadLocalRandom): Long = {
      val n = tlr.nextLong(MAX_VALUE >> 2)
      if (n < 0) -n else n
    }
  }

  @State(Scope.Benchmark)
  class CslmState extends AbstractState {

    val cslm: ConcurrentSkipListMap[Long, Callback] = {
      val m = new ConcurrentSkipListMap[Long, Callback]
      val tlr = ThreadLocalRandom.current()
      for (_ <- 0 until size) {
        m.put(nextDelay(tlr), dummyCallback)
      }
      assert(m.size() >= size / 2)
      m
    }
  }

  @State(Scope.Benchmark)
  class TslState extends AbstractState {

    val tsl: SkipListMap[Long, Callback] = {
      val m = new SkipListMap[Long, Callback]
      val tlr = ThreadLocalRandom.current()
      for (_ <- 0 until size) {
        m.put(key = MIN_VALUE + nextDelay(tlr), value = dummyCallback)
      }
      m
    }
  }
}
