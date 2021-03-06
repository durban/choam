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

import dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1)
@deprecated("so that we can call old methods", since = "we need it")
class PromiseBench extends BenchUtils {

  import PromiseBench._

  final val waitTime = 0L
  final val size = 2048
  final val waitersLess = 2
  final val waitersMore = 128

  @Benchmark
  def lessWaitersBaseline(s: PromiseSt): Unit = {
    baseline(s, waitersLess)
  }

  @Benchmark
  def moreWaitersBaseline(s: PromiseSt): Unit = {
    baseline(s, waitersMore)
  }

  private def baseline(s: PromiseSt, waiters: Int): Unit = {
    val tsk = Promise.slow[IO, String].run[IO].flatMap(task(waiters))
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def lessWaitersOptimized(s: PromiseSt): Unit = {
    optimized(s, waitersLess)
  }

  @Benchmark
  def moreWaitersOptimized(s: PromiseSt): Unit = {
    optimized(s, waitersMore)
  }

  private def optimized(s: PromiseSt, waiters: Int): Unit = {
    val tsk = Promise.fast[IO, String].run[IO].flatMap(task(waiters))
    run(s.runtime, tsk, size = size)
  }

  private def task(waiters: Int)(p: Promise[IO, String]): IO[Unit] = {
    for {
      fibs <- p.get.start.replicateA(waiters)
      _ <- IO.race(p.complete[IO]("left"), p.complete[IO]("right"))
      _ <- fibs.traverse(_.joinWithNever)
    } yield ()
  }
}

object PromiseBench {
  @State(Scope.Benchmark)
  class PromiseSt {
    val runtime = cats.effect.unsafe.IORuntime.global
  }
}
