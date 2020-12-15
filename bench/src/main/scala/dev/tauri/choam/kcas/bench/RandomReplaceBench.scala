/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(2)
@BenchmarkMode(Array(Mode.AverageTime))
class RandomReplaceBench {

  import RandomReplaceBench._

  @Benchmark
  def replaceNever(s: SharedState, bh: Blackhole): Unit = {
    bh.consume(EMCAS.readValue(s.ref, replace = 0))
    s.reset()
  }

  @Benchmark
  def replaceRandom(s: SharedState, bh: Blackhole): Unit = {
    bh.consume(EMCAS.readValue(s.ref, replace = 256))
    s.reset()
  }

  @Benchmark
  def replaceAlways(s: SharedState, bh: Blackhole): Unit = {
    bh.consume(EMCAS.readValue(s.ref, replace = 1))
    s.reset()
  }

  @Benchmark
  def reset(s: SharedState): Unit = {
    s.reset()
  }
}

final object RandomReplaceBench {

  abstract class SharedStateBase[A <: AnyRef](a: A) {

    val ref = Ref.mk[A](nullOf[A])

    reset()

    def reset(): Unit = {
      val wd = BenchmarkAccess.newWeakData[A](null, a)
      this.ref.unsafeSet(wd.asInstanceOf[A])
    }
  }

  @State(Scope.Benchmark)
  class SharedState extends SharedStateBase[String]("x")
}
