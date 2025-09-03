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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import core.{ Rxn, Ref }
import util._

/** Compares the performance of `flatMap` and `*>` */
@Fork(3)
@Threads(2)
class FlatMapBench {

  @Benchmark
  def withFlatMap(s: FlatMapBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % FlatMapBench.size
    val r: Rxn[String] = s.rsWithFlatMap(idx)
    bh.consume(r.unsafePerform(k.mcasImpl))
  }

  @Benchmark
  def withStarGreater(s: FlatMapBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % FlatMapBench.size
    val r: Rxn[String] = s.rsWithStarGreater(idx)
    bh.consume(r.unsafePerform(k.mcasImpl))
  }
}

object FlatMapBench {

  final val size = 8
  final val n = 16

  sealed abstract class OpType extends Product with Serializable
  final case object FlatMap extends OpType
  final case object StarGreater extends OpType

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {

    private[this] val dummy: Rxn[Unit] =
      Rxn.unsafe.delay { () }

    private[this] val refs = List.fill(size) {
      Ref.unsafe[String](
        ThreadLocalRandom.current().nextInt().toString,
        Ref.AllocationStrategy.Padded,
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    private[this] val addXs: List[Rxn[Unit]] =
      refs.map(_.update(_.substring(1) + "x"))

    private[this] val addYs: List[Rxn[String]] =
      refs.map(_.updateAndGet(_.substring(1) + "y"))

    private[this] final def mkReactions(opType: OpType): List[Rxn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = opType)
      }
    }

    val rsWithFlatMap: List[Rxn[String]] =
      mkReactions(opType = FlatMap)

    val rsWithStarGreater: List[Rxn[String]] =
      mkReactions(opType = StarGreater)

    private[this] def buildReaction(n: Int, first: Rxn[Unit], last: Rxn[String], opType: OpType): Rxn[String] = {
      def go(n: Int, acc: Rxn[Unit]): Rxn[Unit] = {
        if (n < 1) {
          acc
        } else {
          val newAcc = opType match {
            case FlatMap => acc.flatMap { _ => dummy }
            case StarGreater => acc *> dummy
          }
          go(n - 1, newAcc)
        }
      }

      opType match {
        case FlatMap => go(n, first).flatMap { _ => last }
        case StarGreater => go(n, first) *> last
      }
    }
  }
}
