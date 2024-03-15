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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(6)
@BenchmarkMode(Array(Mode.AverageTime))
class GlobalCompareBench {

  @Benchmark
  def baseline(bh: Blackhole): Unit = {
    val r1 = Ref.unsafe("a").loc
    val r2 = Ref.unsafe("a").loc
    bh.consume(r1)
    bh.consume(r2)
  }

  // TODO: this is probably incorrect, results
  // TODO: show IdHash and Hash to be slower,
  // TODO: which is strange; we should do the
  // TODO: measurement on already existing Refs

  @Benchmark
  def benchIdHash(bh: Blackhole): Unit = {
    val r1 = Ref.unsafe("a").loc
    val r2 = Ref.unsafe("a").loc
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(System.identityHashCode(r1) - System.identityHashCode(r2))
  }

  @Benchmark
  def benchHash(bh: Blackhole): Unit = {
    val r1 = Ref.unsafe("a").loc
    val r2 = Ref.unsafe("a").loc
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(r1.## - r2.##)
  }

  @Benchmark
  def bench0(bh: Blackhole): Unit = {
    val r1 = Ref.unsafe("a").loc
    val r2 = Ref.unsafe("a").loc
    bh.consume(r1)
    bh.consume(r2)
    bh.consume(MemoryLocation.globalCompare(r1, r2))
  }
}
