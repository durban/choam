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
import org.openjdk.jmh.infra.Blackhole

import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import _root_.dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1)
class BoundedQueueBench extends BenchUtils {

  final val N = 1024 * 1024

  protected override def waitTime: Long =
    0L

  @Benchmark
  def boundedQueue(s: BoundedQueueBench.St, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.rxnQ, bh, N), 1)
  }

  @Benchmark
  def catsQueue(s: BoundedQueueBench.St, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.catsQ, bh, N), 1)
  }

  def tsk(q: CatsQueue[IO, String], bh: Blackhole, size: Int): IO[Unit] = {
    val halfSize = size / 2
    def produce(size: Int): IO[Unit] = {
      if (size > 0) q.offer("foo") >> produce(size - 1)
      else IO.unit
    }
    def consume(count: Int): IO[Unit] = {
      if (count < size) q.take.flatMap(x => IO(bh.consume(x))) >> consume(count + 1)
      else IO.unit
    }
    IO.both(
      IO.both(produce(halfSize), produce(halfSize)),
      consume(0)
    ).void
  }
}

object BoundedQueueBench {
  @State(Scope.Benchmark)
  class St {
    final val Bound =
      1024
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val catsQ: CatsQueue[IO, String] =
      CatsQueue.bounded[IO, String](Bound).unsafeRunSync()(runtime)
    val rxnQ: CatsQueue[IO, String] =
      BoundedQueue[IO, String](Bound).unsafeRun(kcas.KCAS.EMCAS).toCats
  }
}
