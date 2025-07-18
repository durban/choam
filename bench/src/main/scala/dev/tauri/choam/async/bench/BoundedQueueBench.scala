/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.Parallel
import cats.effect.IO
import cats.effect.std.{ Queue => CatsQueue }

import dev.tauri.choam.bench.BenchUtils
import ce.unsafeImplicits._

@Fork(2)
@Threads(1) // because it runs on the CE threadpool
class BoundedQueueBench extends BenchUtils {

  import BoundedQueueBench._

  // must be divisible by `producers`
  final val N = 1024 * 32 * 12

  @Benchmark
  def boundedLinkedQueue(s: St, ps: ParamSt, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.rxnLinkedQ, bh, N, producers = ps.producers, consumers = ps.consumers), 1)
  }

  @Benchmark
  def boundedArrayQueue(s: St, ps: ParamSt, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.rxnArrayQ, bh, N, producers = ps.producers, consumers = ps.consumers), 1)
  }

  @Benchmark
  def catsQueue(s: St, ps: ParamSt, bh: Blackhole): Unit = {
    this.run(s.runtime, tsk(s.catsQ, bh, N, producers = ps.producers, consumers = ps.consumers), 1)
  }

  def tsk(
    q: CatsQueue[IO, String],
    bh: Blackhole,
    size: Int,
    producers: Int,
    consumers: Int,
  ): IO[Unit] = {
    val sizePerProducer = size / producers
    assert((sizePerProducer * producers) == size)
    val sizePerConsumer = size / consumers
    assert((sizePerConsumer * consumers) == size)
    def produce(size: Int): IO[Unit] = {
      if (size > 0) q.offer("foo") >> produce(size - 1)
      else IO.unit
    }
    def consume(size: Int, count: Int = 0): IO[Unit] = {
      if (count < size) q.take.flatMap(x => IO(bh.consume(x))) >> consume(size, count + 1)
      else IO.unit
    }
    IO.both(
      Parallel.parReplicateA(producers, produce(sizePerProducer)),
      Parallel.parReplicateA(consumers, consume(sizePerConsumer)),
    ).flatMap {
      case (ps, cs) if (ps.length == producers) && (cs.length == consumers) =>
        IO.unit
      case (ps, cs) =>
        IO.raiseError(new AssertionError(s"producers = ${ps.length}; consumers = ${cs.length}"))
    }
  }
}

object BoundedQueueBench {

  @State(Scope.Thread)
  class ParamSt {

    // must be divisible by 2 (see below)
    @Param(Array("2", "4", "6"))
    var producers: Int = 0

    var consumers: Int = 0

    @Setup
    def setup(): Unit = {
      this.consumers = this.producers / 2
      assert((this.consumers * 2) == this.producers)
    }
  }

  @State(Scope.Benchmark)
  class St {
    final val Bound =
      1024
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val catsQ: CatsQueue[IO, String] =
      CatsQueue.bounded[IO, String](Bound).unsafeRunSync()(using runtime)
    val rxnLinkedQ: CatsQueue[IO, String] =
      BoundedQueueImpl.linked[String](Bound).unsafePerform(asyncReactiveForIO.mcasImpl).asCats[IO]
    val rxnArrayQ: CatsQueue[IO, String] =
      BoundedQueueImpl.array[String](Bound).unsafePerform(asyncReactiveForIO.mcasImpl).asCats[IO]
  }
}
