/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas
package bench

import scala.runtime.BoxesRunTime

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import dev.tauri.choam.bench.util.RandomState

@Fork(4)
class FalseSharing {

  import FalseSharing._

  @Benchmark
  @Group("baseline")
  def readBaseline(s: NotContended, bh: Blackhole): Unit = {
    bh.consume(s.rr)
  }

  @Benchmark
  @Group("baseline")
  def writeBaseline(s: NotContended, r: RandomState): Unit = {
    s.rw = BoxesRunTime.boxToInteger(r.nextInt())
  }

  @Benchmark
  @Group("unpadded")
  def readUnpadded(s: Unpadded, bh: Blackhole): Unit = {
    bh.consume(s.rr.unsafeGetVolatile())
  }

  @Benchmark
  @Group("unpadded")
  def writeUnpadded(s: Unpadded, r: RandomState): Unit = {
    s.rw.unsafeSetVolatile(r.nextInt())
  }

  @Benchmark
  @Group("padded")
  def readPadded(s: Padded, bh: Blackhole): Unit = {
    bh.consume(s.rr.unsafeGetVolatile())
  }

  @Benchmark
  @Group("padded")
  def writePadded(s: Padded, r: RandomState): Unit = {
    s.rw.unsafeSetVolatile(r.nextInt())
  }
}

object FalseSharing {

  @State(Scope.Thread)
  class NotContended {
    var rr: AnyRef = BoxesRunTime.boxToInteger(42)
    var rw: AnyRef = BoxesRunTime.boxToInteger(21)
  }

  abstract class Base {

    def rr: Ref[Int]
    def rw: Ref[Int]

    @TearDown
    def checkResults(): Unit = {
      rr.unsafeGetVolatile() match {
        case 42 => // OK
        case x => throw new IllegalStateException(s"unexpected value in rr: '${x}'")
      }
    }
  }

  @State(Scope.Benchmark)
  class Unpadded extends Base {
    final override val rr: Ref[Int] = Ref.unsafeUnpadded(42)
    final override val rw: Ref[Int] = Ref.unsafeUnpadded(21)
  }

  @State(Scope.Benchmark)
  class Padded extends Base {
    final override val rr: Ref[Int] = Ref.unsafe(42)
    final override val rw: Ref[Int] = Ref.unsafe(21)
  }
}
