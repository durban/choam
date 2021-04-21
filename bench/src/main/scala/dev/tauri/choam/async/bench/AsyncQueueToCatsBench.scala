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
import cats.effect.std.{ Queue => CatsQueue }

import dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1)
class AsyncQueueToCatsBench extends BenchUtils {

  import AsyncQueueToCatsBench._

  final override val waitTime = 0L
  final val size = 1024
  final val queueSize = 8

  @Benchmark
  def asyncQueueToCats(s: St): Unit = {
    val tsk = AsyncQueue.withSize[IO, String].run[IO].flatMap(_.toCats).flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def catsQueue(s: St): Unit = {
    val tsk = CatsQueue.unbounded[IO, String].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  private[this] def task(q: CatsQueue[IO, String]): IO[Unit] = {
    for {
      fibs <- q.take.start.replicateA(queueSize)
      _ <- fibs.take(queueSize / 2).traverse(_.cancel)
      _ <- q.offer("x").replicateA(queueSize)
      _ <- fibs.drop(queueSize / 2).traverse(_.joinWithNever)
    } yield ()
  }
}

object AsyncQueueToCatsBench {
  @State(Scope.Benchmark)
  class St {
    val runtime = cats.effect.unsafe.IORuntime.global
  }
}
