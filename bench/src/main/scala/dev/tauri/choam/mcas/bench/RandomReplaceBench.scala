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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class RandomReplaceBench {

  import RandomReplaceBench._

  @Benchmark
  def replaceNever(s: SharedState, bh: Blackhole): Unit = {
    val ctx = Emcas.currentContext()
    bh.consume(Emcas.readValue(s.ref, ctx, replace = 0))
    s.reset()
  }

  @Benchmark
  def replaceAlways(s: SharedState, bh: Blackhole): Unit = {
    val ctx = Emcas.currentContext()
    bh.consume(Emcas.readValue(s.ref, ctx, replace = 1))
    s.reset()
  }

  @Benchmark
  def reset(s: SharedState): Unit = {
    val _ = Emcas.currentContext()
    s.reset()
  }
}

object RandomReplaceBench {

  abstract class SharedStateBase[A <: AnyRef](a: A) {

    val ref: MemoryLocation[A] =
      Ref.unsafe[A](nullOf[A]).loc

    reset()

    def reset(): Unit = {
      val h = Emcas.currentContext().start().add(
        HalfWordDescriptor[A](ref, a, a, Version.Start)
      )
      val p = EmcasDescriptor.prepare(h)
      val wd = p.wordIterator().next()
      assert(BenchmarkAccess.casStatusFromActiveToFailedVal(p))
      this.ref.unsafeSetVolatile(wd.cast[A].castToData)
    }
  }

  @State(Scope.Benchmark)
  class SharedState extends SharedStateBase[String]("x")
}
