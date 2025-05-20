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
package bench
package rxn

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

import org.openjdk.jmh.annotations._

import core.{ Rxn, Axn, RetryStrategy }
import util.McasImplStateBase

@Threads(1)
@BenchmarkMode(Array(Mode.AverageTime))
class RetryBench {

  import RetryBench._

  @Benchmark
  def dontRetry1000(st: StDont): Unit = {
    st.rxn.perform[IO, String](null, st.mcasImpl, st.str).replicateA_(R * 1000).unsafeRunSync()(st.rt)
  }

  @Benchmark
  def retry010k(st: St10k): Unit = {
    IO { new AtomicInteger }.flatMap { ctr =>
      st.rxn.perform[IO, String](ctr, st.mcasImpl, st.str)
    }.replicateA_(R).unsafeRunSync()(st.rt)
  }

  @Benchmark
  def retry100k(st: St100k): Unit = {
    IO { new AtomicInteger }.flatMap { ctr =>
      st.rxn.perform[IO, String](ctr, st.mcasImpl, st.str)
    }.replicateA_(R).unsafeRunSync()(st.rt)
  }
}

object RetryBench {

  final val R = 100

  @State(Scope.Thread)
  class StDont extends McasImplStateBase {

    private[this] var ctr: Int =
      0

    val rxn: Rxn[Any, String] = {
      Axn.unsafe.delay {
        this.ctr += 1
        0
      }.flatMapF { _ =>
        Axn.pure("foo")
      }
    }

    val str: RetryStrategy =
      RetryStrategy.cede()

    val rt =
      cats.effect.unsafe.IORuntime.global
  }

  @State(Scope.Thread)
  class St10k extends St(10000)

  @State(Scope.Thread)
  class St100k extends St(100000)

  @State(Scope.Thread)
  abstract class St(N: Int) extends McasImplStateBase {

    val rxn: Rxn[AtomicInteger, String] = Rxn.computed { ctr =>
      Axn.unsafe.delay { ctr.incrementAndGet() }.flatMapF { c =>
        if (c > N) Axn.pure("foo")
        else Rxn.unsafe.retry
      }
    }

    val str: RetryStrategy =
      RetryStrategy.cede()

    val rt =
      cats.effect.unsafe.IORuntime.global
  }
}
