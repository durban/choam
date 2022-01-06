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

import data.GcHostileMsQueue
import dev.tauri.choam.bench.util.{ Prefill, KCASImplState }

@Fork(value = 6, jvmArgsAppend = Array("-Xmx2048M"))
@Threads(2)
class GcBench {

  import GcBench._

  @Benchmark
  def qTransfer(s: SharedState, m: KCASImplState): Unit = {
    val ctx = m.kcasCtx
    var idx = 0
    while (idx < s.size) {
      s.transferOne(idx).unsafePerformInternal(a = null : Any, ctx = ctx)
      idx += 1
    }
  }
}

object GcBench {

  @State(Scope.Benchmark)
  class SharedState {

    final val circleSize = 4

    final val size = 4096

    val circle: List[GcHostileMsQueue[String]] = List.fill(circleSize) {
      GcHostileMsQueue.fromList(Prefill.prefill().toList).unsafeRun(EMCAS)
    }

    def transferOne(idx: Int): Axn[Unit] = {
      circle(idx % circleSize).tryDeque.map(_.get) >>> circle((idx + 1) % circleSize).enqueue
    }
  }
}
