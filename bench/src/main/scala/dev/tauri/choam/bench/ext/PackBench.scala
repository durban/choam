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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util.RandomState

@Fork(value = 2) // , jvmArgsAppend = Array("-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintIntrinsics"))
@Threads(1)
@BenchmarkMode(Array(Mode.AverageTime))
class PackBench { // see HAMT

  @Benchmark
  def packSizeAndBlue(r: RandomState): Int = {
    Hamt_packSizeAndBlueInternal(r.nextInt(), r.nextBoolean())
  }

  private[this] final def Hamt_packSizeAndBlueInternal(size: Int, isBlue: Boolean): Int = {
    val x = (-1) * java.lang.Math.abs(java.lang.Boolean.compare(isBlue, true))
    size * ((x << 1) + 1)
  }

  @Benchmark
  def packSizeAndBlueExperimental(r: RandomState): Int = {
    packSizeAndBlueExperimental(r.nextInt(), r.nextBoolean())
  }

  private[this] final def packSizeAndBlueExperimental(size: Int, isBlue: Boolean): Int = {
    if (isBlue) size
    else -size
  }

  @Benchmark
  def packSizeDiffAndBlue(r: RandomState): Int = {
    MutHamt_packSizeDiffAndBlue(r.nextIntBounded(2), r.nextBoolean())
  }

  private[this] final def MutHamt_packSizeDiffAndBlue(sizeDiff: Int, isBlue: Boolean): Int = {
    val bl = java.lang.Math.abs(java.lang.Boolean.compare(isBlue, false)) << 1
    bl | sizeDiff
  }

  @Benchmark
  def packSizeDiffAndBlueExperimental(r: RandomState): Int = {
    packSizeDiffAndBlueExperimental(r.nextIntBounded(2), r.nextBoolean())
  }

  private[this] final def packSizeDiffAndBlueExperimental(sizeDiff: Int, isBlue: Boolean): Int = {
    val bl = if (isBlue) 2 else 0
    bl | sizeDiff
  }

  @Benchmark
  def mutHamtRootSize(s: PackBench.StRoot, r: RandomState): Unit = {
    val sz = s.size
    val d = if (sz > 16777215) {
      -16777215
    } else {
      r.nextInt() % 7
    }
    s.addToSize(d)
  }

  @Benchmark
  def mutHamtRootIsBlueTree(s: PackBench.StRoot, r: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.isBlueTree)
    s.isBlueTree = r.nextBoolean()
  }

  @Benchmark
  def mutHamtRootSizeExperimental(s: PackBench.StRootExperimental, r: RandomState): Unit = {
    val sz = s.size
    val d = if (sz > 16777215) {
      -16777215
    } else {
      r.nextInt() % 7
    }
    s.addToSize(d)
  }

  @Benchmark
  def mutHamtRootIsBlueTreeExperimental(s: PackBench.StRootExperimental, r: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.isBlueTree)
    s.isBlueTree = r.nextBoolean()
  }
}

object PackBench {

  @State(Scope.Thread)
  class StRoot {

    private var logIdx: Int =
      0

    final def size: Int = {
      java.lang.Math.abs(this.logIdx)
    }

    final def addToSize(s: Int): Unit = {
      val logIdx = this.logIdx
      val x = -(logIdx >>> 31)
      this.logIdx = java.lang.Math.addExact(logIdx, ((x << 1) + 1) * s)
    }

    final def isBlueTree: Boolean = {
      this.logIdx >= 0
    }

    final def isBlueTree_=(isBlue: Boolean): Unit = {
      val x = (-1) * java.lang.Math.abs(java.lang.Boolean.compare(isBlue, this.isBlueTree))
      this.logIdx *= (x << 1) + 1
    }
  }

  @State(Scope.Thread)
  class StRootExperimental {

    private var logIdx: Int =
      0

    final def size: Int = {
      this.logIdx & 0x7fffffff
    }

    final def addToSize(s: Int): Unit = {
      val logIdx = this.logIdx
      val newSize = java.lang.Math.addExact(logIdx & 0x7fffffff, s)
      val bit = logIdx & 0x80000000
      this.logIdx = newSize | bit
    }

    final def isBlueTree: Boolean = {
      this.logIdx < 0
    }

    final def isBlueTree_=(isBlue: Boolean): Unit = {
      val logIdx = this.logIdx
      val bit = if (isBlue) 0x80000000 else 0x0
      this.logIdx = (logIdx & 0x7fffffff) | bit
    }
  }
}
