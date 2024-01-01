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

import data.{ Queue, QueueHelper }
import dev.tauri.choam.bench.util.{ Prefill, McasImplState }
import ce._

@Fork(value = 6, jvmArgsAppend = Array("-Xmx2048M"))
@Threads(2)
class GcBench {

  import GcBench._

  @Benchmark
  def gcHostile(s: GcHostileSt, m: McasImplState): Unit = {
    val ctx = m.mcasCtx
    var idx = 0
    while (idx < s.size) {
      s.transferOne(idx).unsafePerformInternal(a = null : Any, ctx = ctx)
      idx += 1
    }
  }

  @Benchmark
  def msQueue(s: MsQueueSt, m: McasImplState): Unit = {
    val ctx = m.mcasCtx
    var idx = 0
    while (idx < s.size) {
      s.transferOne(idx).unsafePerformInternal(a = null : Any, ctx = ctx)
      idx += 1
    }
  }
}

object GcBench {

  @State(Scope.Benchmark)
  abstract class BaseState {

    final val circleSize = 4

    final val size = 4096

    def circle: List[Queue[String]]

    final def transferOne(idx: Int): Axn[Unit] = {
      circle(idx % circleSize).tryDeque.map(_.get) >>> circle((idx + 1) % circleSize).enqueue
    }
  }

  @State(Scope.Benchmark)
  class GcHostileSt extends BaseState {

    private[this] val _circle: List[Queue[String]] = List.fill(circleSize) {
      QueueHelper.gcHostileMsQueueFromList[cats.effect.SyncIO, String](
        Prefill.prefill().toList
      ).unsafeRunSync()
    }

    final override def circle: List[Queue[String]] =
      _circle
  }

  @State(Scope.Benchmark)
  class MsQueueSt extends BaseState {

    private[this] val _circle: List[Queue[String]] = List.fill(circleSize) {
      QueueHelper.msQueueFromList[cats.effect.SyncIO, String](
        Prefill.prefill().toList
      ).unsafeRunSync()
    }

    final override def circle: List[Queue[String]] =
      _circle
  }
}
