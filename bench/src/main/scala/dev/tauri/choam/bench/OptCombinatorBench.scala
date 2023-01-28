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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

/** Compares the performance of derived and primitive combinators */
@Fork(3)
class OptCombinatorBench {

  @Benchmark
  def providePrimitive(s: OptCombinatorBench.St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.provide.unsafePerformInternal(null, k.mcasCtx))
  }

  @Benchmark
  def provideWithContramap(s: OptCombinatorBench.St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.contramap.unsafePerformInternal(null, k.mcasCtx))
  }

  @Benchmark
  def asPrimitive(s: OptCombinatorBench.St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.as.unsafePerformInternal(null, k.mcasCtx))
  }

  @Benchmark
  def asWithMap(s: OptCombinatorBench.St, bh: Blackhole, k: McasImplState): Unit = {
    bh.consume(s.map.unsafePerformInternal(null, k.mcasCtx))
  }
}

object OptCombinatorBench {

  @State(Scope.Benchmark)
  class St {

    val ref: Ref[String] =
      Ref.unsafe("a")

    val provide: Axn[String] =
      ref.getAndSet.provide("x")
    val contramap: Axn[String] =
      ref.getAndSet.provideOld("x")

    val as: Axn[String] =
      ref.get.as("x")
    val map: Axn[String] =
      ref.get.asOld("x")
  }
}
