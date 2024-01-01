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
package async
package bench

import org.openjdk.jmh.annotations._

import cats.syntax.all._
import cats.effect.{ IO, SyncIO }
import cats.effect.unsafe.IORuntime

import _root_.dev.tauri.choam.bench.BenchUtils
import ce._

@Fork(2)
@Threads(1)
class AsyncStackBench extends BenchUtils {

  import AsyncStackBench._

  final val size = 2048
  final val stackSize = 4
  final val multiplier = 16

  // simple push/pop:

  @Benchmark
  @Group("stack3pp")
  def stack3push(s: StackSt): Unit = {
    val tsk = push(s.stack3)
    run(s.runtime, tsk, size = size)
  }

  @Benchmark
  @Group("stack3pp")
  def stack3pop(s: StackSt): Unit = {
    val tsk = pop(s.stack3)
    run(s.runtime, tsk, size = size)
  }

  private[this] def push(s: AsyncStack[IO, String]): IO[Unit] = {
    def go(left: Int): IO[Unit] = {
      if (left > 0) s.push[IO]("foo") >> go(left - 1)
      else IO.unit
    }
    go(stackSize * multiplier)
  }

  private[this] def pop(s: AsyncStack[IO, String]): IO[Unit] = {
    def go(left: Int): IO[Unit] = {
      if (left > 0) s.pop >> go(left - 1)
      else IO.unit
    }
    go(stackSize * multiplier)
  }

  // async features:

  @Benchmark
  def asyncStack3(s: StackSt): Unit = {
    val tsk = task(s.stack3)
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
    val runtime: IORuntime = cats.effect.unsafe.IORuntime.global
    val stack3: AsyncStack[IO, String] = AsyncStack.treiberStack[IO, String].run[SyncIO].unsafeRunSync()
  }
}
