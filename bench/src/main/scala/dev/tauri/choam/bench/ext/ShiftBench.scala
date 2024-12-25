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
package bench
package ext

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.annotations._

@Fork(value = 2) //, jvmArgsAppend = Array("-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintIntrinsics"))
@Threads(1)
class ShiftBench { // see HAMT

  @Benchmark
  def lowestFirst(r: ShiftBench.St, bh: Blackhole): Unit = {
    val arr = r.arr
    var idx = 0
    val len = arr.length
    while (idx < len) {
      val n = arr(idx)
      var shift = 0
      while (shift < 64) {
        bh.consume(_lowestFirst(n, shift))
        shift += 1
      }
      idx += 1
    }
  }

  private[this] final def _lowestFirst(n: Long, shift: Int): Int = {
    ((n >>> shift) & 63L).toInt
  }

  @Benchmark
  def highestFirst(r: ShiftBench.St, bh: Blackhole): Unit = {
    val arr = r.arr
    var idx = 0
    val len = arr.length
    while (idx < len) {
      val n = arr(idx)
      var shift = 0
      while (shift < 64) {
        bh.consume(_highestFirst(n, shift))
        shift += 1
      }
      idx += 1
    }
  }

  private[this] final def _highestFirst(n: Long, shift: Int): Int = {
    val mask = 0xFC00000000000000L >>> shift
    val sh = java.lang.Long.numberOfTrailingZeros(mask)
    ((n & mask) >>> sh).toInt
  }

  @Benchmark
  def highestFirstExperimental(r: ShiftBench.St, bh: Blackhole): Unit = {
    val arr = r.arr
    var idx = 0
    val len = arr.length
    while (idx < len) {
      val n = arr(idx)
      var shift = 0
      while (shift < 64) {
        bh.consume(_highestFirstExperimental(n, shift))
        shift += 1
      }
      idx += 1
    }
  }

  private[this] final def _highestFirstExperimental(n: Long, shift: Int): Int = {
    ((n << shift) >>> 58).toInt
  }
}

object ShiftBench {
  @State(Scope.Thread)
  class St {
    val arr: Array[Long] = {
      val a = new Array[Long](4096)
      var idx = 0
      while (idx < a.length) {
        a(idx) = ThreadLocalRandom.current().nextLong()
        idx += 1
      }
      a
    }
  }
}
