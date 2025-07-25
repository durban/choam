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
package async
package bench

import org.openjdk.jmh.annotations._

import cats.syntax.all._
import cats.effect.IO

import dev.tauri.choam.bench.BenchUtils
import dev.tauri.choam.bench.util.McasImplStateBase

@Fork(2)
@Threads(1)
class AsyncUnboundedQueueBench extends BenchUtils {

  import AsyncUnboundedQueueBench.St

  final val size = 2048
  final val queueSize = 4

  @Benchmark
  def unboundedQueueSimple(s: St): Unit = {
    import s.reactive
    val tsk = AsyncQueue.unbounded[String].run[IO].flatMap(task(_, s))
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def unboundedQueueWithSize(s: St): Unit = {
    import s.reactive
    val tsk = AsyncQueue.unboundedWithSize[String].run[IO].flatMap(task(_, s))
    run(s.runtime, tsk, size = size)
  }

  private[this] def task(q: AsyncQueue[String], s: St): IO[Unit] = {
    import s.reactive
    for {
      fibs <- q.take.start.replicateA(queueSize)
      _ <- fibs.take(queueSize / 2).traverse(_.cancel)
      _ <- q.put[IO]("x").replicateA(queueSize)
      _ <- fibs.drop(queueSize / 2).traverse(_.joinWithNever)
    } yield ()
  }
}

object AsyncUnboundedQueueBench {
  @State(Scope.Benchmark)
  class St extends McasImplStateBase {
    val runtime = cats.effect.unsafe.IORuntime.global
  }
}
