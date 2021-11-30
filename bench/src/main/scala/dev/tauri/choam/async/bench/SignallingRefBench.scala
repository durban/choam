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

import cats.effect.IO
import fs2.concurrent.SignallingRef

import _root_.dev.tauri.choam.bench.BenchUtils

@Fork(2)
@Threads(1)
class SignallingRefBench extends BenchUtils {

  final val N = 1024 * 1024
  final val End = "END"

  protected override def waitTime: Long =
    0L

  @Benchmark
  def rxnSignallingRef(s: SignallingRefBench.St): Unit = {
    this.run(s.runtime, tsk(s.rxn, s.rxnReset, N), 1)
  }

  @Benchmark
  def fs2SignallingRef(s: SignallingRefBench.St): Unit = {
    this.run(s.runtime, tsk(s.fs2, s.fs2Reset, N), 1)
  }

  def tsk(r: SignallingRef[IO, String], reset: IO[Unit], size: Int): IO[Unit] = {
    def produce(size: Int): IO[Unit] = {
      if (size > 0) r.set("foo") >> produce(size - 1)
      else r.set(End)
    }
    def consume: IO[Unit] = {
      r.discrete.takeWhile(_ ne End).compile.drain
    }
    IO.both(
      produce(size),
      IO.both(consume, consume).void
    ).void.guarantee(reset)
  }
}

object SignallingRefBench {
  @State(Scope.Benchmark)
  class St {

    val runtime =
      cats.effect.unsafe.IORuntime.global
    val fs2: SignallingRef[IO, String] =
      SignallingRef.of[IO, String]("initial").unsafeRunSync()(runtime)
    val fs2Reset: IO[Unit] =
      reset(fs2)
    val rxn: SignallingRef[IO, String] =
      stream.signallingRef[IO, String]("initial").unsafeRun(kcas.KCAS.EMCAS)
    val rxnReset: IO[Unit] =
      reset(rxn)

    private[this] def reset(ref: SignallingRef[IO, String]): IO[Unit] = {
      ref.set("initial")
    }
  }
}
