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
package bench

import org.openjdk.jmh.infra.Blackhole

import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import zio.Task
import zio.{ Runtime => ZRuntime }

trait BenchUtils {

  protected def waitTime: Long =
    0L

  protected final def run(rt: IORuntime, task: IO[Unit], size: Int): Unit = {
    IO.asyncForIO.replicateA(size, task).unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  protected final def runIdx(rt: IORuntime, task: Int => IO[Unit], size: Int): Unit = {
    List.tabulate(size) { idx => task(idx) }.sequence.void.unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  protected final def runZ(rt: ZRuntime[_], task: Task[Unit], size: Int): Unit = {
    zio.Unsafe.unsafe { implicit u =>
      rt.unsafe.run(task.repeatN(size - 1)).getOrThrow()
    }
    Blackhole.consumeCPU(waitTime)
  }

  protected final def runIdxZ(rt: ZRuntime[_], task: Int => Task[Unit], size: Int): Unit = {
    zio.Unsafe.unsafe { implicit u =>
      rt.unsafe.run(zio.ZIO.foreachDiscard((0 until size).toList) { idx => task(idx) }).getOrThrow()
    }
    Blackhole.consumeCPU(waitTime)
  }

  protected final def runRepl(rt: IORuntime, task: IO[Unit], size: Int, parallelism: Int): Unit = {
    val n = size / parallelism
    assert((n * parallelism) == size) // avoid rounding error
    task.replicateA(n).unsafeRunSync()(rt) : List[Unit]
    ()
  }

  protected final def runReplZ(rt: ZRuntime[_], task: Task[Unit], size: Int, parallelism: Int): Unit = {
    val n = size / parallelism
    assert((n * parallelism) == size) // avoid rounding error
    zio.Unsafe.unsafe { implicit u =>
      rt.unsafe.run(task.replicateZIO(n)).getOrThrow() : Iterable[Unit]
    }
    ()
  }
}
