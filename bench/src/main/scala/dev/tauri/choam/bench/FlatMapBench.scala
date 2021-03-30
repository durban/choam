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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import kcas.Ref
import util._
import org.openjdk.jmh.infra.Blackhole

/** Compares the performance of `x.flatMap { _ => y }` and `x *> y` */
@Fork(5)
class FlatMapBench {

  @Benchmark
  def withFlatMap(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Action[String] = s.rsWithFlatMap(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def withStarGreater(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Action[String] = s.rsWithStarGreater(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }
}

object FlatMapBench {

  final val size = 8
  final val n = 16

  @State(Scope.Benchmark)
  class St {

    private[this] val dummy =
      React.token.void

    private[this] val refs = List.fill(size) {
      Ref.unsafe[String](ThreadLocalRandom.current().nextInt().toString)
    }

    private[this] val addXs: List[Action[Unit]] =
      refs.map(_.update(_.substring(1) + "x"))

    private[this] val addYs: List[Action[String]] =
      refs.map(_.updateAndGet(_.substring(1) + "y"))

    val rsWithFlatMap = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), isFlatMap = true)
      }
    }

    val rsWithStarGreater = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), isFlatMap = false)
      }
    }

    private[this] def buildReaction(n: Int, first: Action[Unit], last: Action[String], isFlatMap: Boolean): Action[String] = {
      def go(n: Int, acc: Action[Unit]): Action[Unit] = {
        if (n < 1) {
          acc
        } else {
          val newAcc = if (isFlatMap) acc.flatMap { _ => dummy } else acc *> dummy
          go(n - 1, newAcc)
        }
      }
      if (isFlatMap) {
        go(n, first).flatMap { _ => last }
      } else {
        go(n, first) *> last
      }
    }
  }
}
