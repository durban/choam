/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
import ce._

@Fork(2)
@Threads(1) // because it runs on the IO compute pool
class PromiseBench extends BenchUtils {

  import PromiseBench._

  final val size = 2048

  @Benchmark
  def promiseOptimized(s: PromiseSt): Unit = {
    optimized(s, s.numWaiters)
  }

  private[this] def optimized(s: PromiseSt, waiters: Int): Unit = {
    val tsk = Promise[IO, String].run[IO].flatMap(task(waiters))
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def promiseOptimizedSingle(s: PromiseSt): Unit = {
    val tsk = Promise[IO, String].run[IO].flatMap(taskSingle)
    run(s.runtime, tsk, size = size)
  }

  private[this] def task(waiters: Int)(p: Promise[IO, String]): IO[Unit] = {
    for {
      fibs <- p.get.start.replicateA(waiters)
      _ <- IO.race(p.complete[IO]("left"), p.complete[IO]("right"))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }

  private[this] def taskSingle(p: Promise[IO, String]): IO[Unit] = {
    p.get.start.flatMap { fib =>
      IO.race(p.complete[IO]("left"), p.complete[IO]("right")) >> (
        fib.joinWithNever
      )
    }.void
  }
}

object PromiseBench {

  @State(Scope.Benchmark)
  class PromiseSt {

    val runtime = cats.effect.unsafe.IORuntime.global

    @Param(Array("2", "4", "6", "8", "64", "128"))
    private[choam] var _numWaiters: Int = 2

    def numWaiters: Int =
      this._numWaiters
  }
}
