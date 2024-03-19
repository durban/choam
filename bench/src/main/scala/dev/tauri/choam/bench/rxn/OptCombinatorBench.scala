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
package bench
package rxn

import java.util.concurrent.ThreadLocalRandom

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

  @Benchmark
  def tailRecMPrimitive(s: OptCombinatorBench.TrecSt, k: McasImplState): String = {
    s.trecM.unsafePerform(null, k.mcasImpl)
  }

  @Benchmark
  def tailRecMWithFlatMap(s: OptCombinatorBench.TrecSt, k: McasImplState): String = {
    s.trecMWithFlatMap.unsafePerform(null, k.mcasImpl)
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

  @State(Scope.Benchmark)
  class TrecSt {

    @Param(Array("8", "128", "1024"))
    protected var size: Int =
      -1

    val ref: Ref[String] =
      Ref.unsafe("a")

    def trecM: Axn[String] =
      this._trecM

    private[this] var _trecM: Axn[String] =
      null

    def trecMWithFlatMap: Axn[String] =
      this._trecMWithFlatMap

    private[this] var _trecMWithFlatMap: Axn[String] =
      null

    @Setup
    def setup(): Unit = {
      val list = List.fill(this.size) {
        ThreadLocalRandom.current().nextInt().toString()
      }
      this._trecM = Rxn.tailRecM(list) {
        case h :: t =>
          ref.getAndSet.provide(h).as(Left(t))
        case Nil =>
          ref.get.map(Right(_))
      }
      this._trecMWithFlatMap = Rxn.tailRecMWithFlatMap(list) {
        case h :: t =>
          ref.getAndSet.provide(h).as(Left(t))
        case Nil =>
          ref.get.map(Right(_))
      }
    }
  }
}
