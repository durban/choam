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
package bench

import org.openjdk.jmh.infra.Blackhole

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import zio.Task
import zio.{ Runtime => ZRuntime }

trait BenchUtils {

  protected def waitTime: Long

  protected final def run(rt: IORuntime, task: IO[Unit], size: Int): Unit = {
    IO.asyncForIO.replicateA(size, task).unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  protected final def runZ(rt: ZRuntime[_], task: Task[Unit], size: Int): Unit = {
    rt.unsafeRunTask(task.repeatN(size))
    Blackhole.consumeCPU(waitTime)
  }

  protected final def isEnq(r: util.RandomState): IO[Boolean] =
    IO { (r.nextInt() % 2) == 0 }

  protected final def isEnqZ(r: util.RandomState): Task[Boolean] =
    Task.effect { (r.nextInt() % 2) == 0 }
}
