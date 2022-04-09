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
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

/** Compares the performance of `flatMap`, `flatMapF`, and `*>` */
@Fork(3)
@Threads(2)
class FlatMapBench {

  @Benchmark
  def withFlatMap(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Axn[String] = s.rsWithFlatMap(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def withFlatMapF(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Axn[String] = s.rsWithFlatMapF(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def withFlatMapFOld(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Axn[String] = s.rsWithFlatMapFOld(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }

  @Benchmark
  def withStarGreater(s: FlatMapBench.St, bh: Blackhole, k: KCASImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % ArrowBench.size
    val r: Axn[String] = s.rsWithStarGreater(idx)
    bh.consume(r.unsafePerform((), k.kcasImpl))
  }
}

object FlatMapBench {

  final val size = 8
  final val n = 16

  sealed abstract class OpType extends Product with Serializable
  final case object FlatMap extends OpType
  final case object FlatMapF extends OpType
  final case object FlatMapFOld extends OpType
  final case object StarGreater extends OpType

  @State(Scope.Benchmark)
  class St {

    private[this] val dummy: Axn[Unit] =
      Rxn.unsafe.delay { _ => () }

    private[this] val refs = List.fill(size) {
      Ref.unsafe[String](ThreadLocalRandom.current().nextInt().toString)
    }

    private[this] val addXs: List[Axn[Unit]] =
      refs.map(_.update(_.substring(1) + "x"))

    private[this] val addYs: List[Axn[String]] =
      refs.map(_.updateAndGet(_.substring(1) + "y"))

    val rsWithFlatMap: List[Axn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = FlatMap)
      }
    }

    val rsWithFlatMapF: List[Axn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = FlatMapF)
      }
    }

    val rsWithFlatMapFOld: List[Axn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = FlatMapFOld)
      }
    }

    val rsWithStarGreater: List[Axn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = StarGreater)
      }
    }

    private[this] def buildReaction(n: Int, first: Axn[Unit], last: Axn[String], opType: OpType): Axn[String] = {
      def go(n: Int, acc: Axn[Unit]): Axn[Unit] = {
        if (n < 1) {
          acc
        } else {
          val newAcc = opType match {
            case FlatMap => acc.flatMap { _ => dummy }
            case FlatMapF => acc.flatMapF { _ => dummy }
            case FlatMapFOld => acc.flatMapFOld { _ => dummy }
            case StarGreater => acc *> dummy
          }
          go(n - 1, newAcc)
        }
      }

      opType match {
        case FlatMap => go(n, first).flatMap { _ => last }
        case FlatMapF => go(n, first).flatMapF { _ => last }
        case FlatMapFOld => go(n, first).flatMapFOld { _ => last }
        case StarGreater => go(n, first) *> last
      }
    }
  }
}
