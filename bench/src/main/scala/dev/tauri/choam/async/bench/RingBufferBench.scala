/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
class RingBufferBench extends BenchUtils {

  final val N = 1024 * 1024

  @Benchmark
  def ringBufferStrict(s: RingBufferBench.St, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.rxnQStrict, bh, N), 1)
  }

  @Benchmark
  def ringBufferLazy(s: RingBufferBench.St, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.rxnQLazy, bh, N), 1)
  }

  @Benchmark
  def catsQueue(s: RingBufferBench.St, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.catsQ, bh, N), 1)
  }

  def tsk(q: CatsQueue[IO, String], bh: Blackhole, size: Int): IO[Unit] = {
    def produce(size: Int): IO[Unit] = {
      if (size > 0) q.offer("foo") >> produce(size - 1)
      else q.offer("END")
    }
    def consume: IO[Unit] = {
      q.take.flatMap { s =>
        if (s eq "END") IO.unit
        else IO(bh.consume(s)) >> consume
      }
    }
    IO.both(produce(size), consume).void
  }
}

object RingBufferBench {
  @State(Scope.Benchmark)
  class St {
    final val Capacity =
      256
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val catsQ: CatsQueue[IO, String] =
      CatsQueue.circularBuffer[IO, String](Capacity).unsafeRunSync()(runtime)
    val rxnQStrict: CatsQueue[IO, String] =
      OverflowQueue.ringBuffer[IO, String](Capacity).unsafeRun(mcas.Mcas.Emcas).toCats
    val rxnQLazy: CatsQueue[IO, String] =
      OverflowQueue.lazyRingBuffer[IO, String](Capacity).unsafeRun(mcas.Mcas.Emcas).toCats
  }
}
