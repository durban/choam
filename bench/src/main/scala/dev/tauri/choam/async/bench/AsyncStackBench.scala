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
class AsyncStackBench extends BenchUtils {

  import AsyncStackBench._

  final override val waitTime = 0L
  final val size = 2048
  final val stackSize = 4

  @Benchmark
  def asyncStack1(s: StackSt): Unit = {
    val tsk = AsyncStack.impl1[IO, String].run[IO].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  def asyncStack2(s: StackSt): Unit = {
    val tsk = AsyncStack.impl2[IO, String].run[IO].flatMap(task)
    run(s.runtime, tsk, size = size)
  }

  private[this] def task(s: AsyncStack[IO, String]): IO[Unit] = {
    for {
      fibs <- s.pop.start.replicateA(stackSize)
      _ <- fibs.take(stackSize / 2).traverse(_.cancel)
      _ <- s.push[IO]("x").replicateA(stackSize)
      _ <- fibs.drop(stackSize / 2).traverse(_.joinWithNever)
    } yield ()
  }
}

object AsyncStackBench {
  @State(Scope.Benchmark)
  class StackSt {
    val runtime = cats.effect.unsafe.IORuntime.global
  }
}
