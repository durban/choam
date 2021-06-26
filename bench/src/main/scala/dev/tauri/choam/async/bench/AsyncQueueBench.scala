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
package async
package bench

import org.openjdk.jmh.annotations._

import cats.syntax.all._
import cats.effect.IO

import _root_.dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1)
class AsyncQueueBench extends BenchUtils {

  import AsyncQueueBench._

  final override val waitTime = 0L
  final val size = 2048
  final val queueSize = 4

  @Benchmark
  def asyncQueuePrimitive(s: St): Unit = {
    val tsk = AsyncQueue.primitive[IO, String].run[IO].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def asyncQueueDerived(s: St): Unit = {
    val tsk = AsyncQueue.derived[IO, String].run[IO].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def asyncQueueWithSize(s: St): Unit = {
    val tsk = AsyncQueue.withSize[IO, String].run[IO].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  private[this] def task(q: AsyncQueue[IO, String]): IO[Unit] = {
    for {
      fibs <- q.deque.start.replicateA(queueSize)
      _ <- fibs.take(queueSize / 2).traverse(_.cancel)
      _ <- q.enqueue[IO]("x").replicateA(queueSize)
      _ <- fibs.drop(queueSize / 2).traverse(_.joinWithNever)
    } yield ()
  }
}

object AsyncQueueBench {
  @State(Scope.Benchmark)
  class St {
    val runtime = cats.effect.unsafe.IORuntime.global
  }
}
