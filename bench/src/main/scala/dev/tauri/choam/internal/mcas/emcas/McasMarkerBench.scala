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
package internal
package mcas
package emcas

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@Fork(3)
@Threads(2)
@BenchmarkMode(Array(Mode.AverageTime))
class McasMarkerBench {

  @Benchmark
  def anyRef(bh: Blackhole): Unit = {
    bh.consume(new AnyRef)
  }

  @Benchmark
  def mcasMarker(bh: Blackhole): Unit = {
    bh.consume(new McasMarker)
  }

  @Benchmark
  def xBaseline(bh: Blackhole): Unit = {
    bh.consume(bh)
  }
}
