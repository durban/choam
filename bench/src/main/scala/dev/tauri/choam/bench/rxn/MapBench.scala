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

import cats.Monad

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

/** Compares the performance of `map`, either called directly or through the `Monad` instance */
@Fork(3)
@Threads(2)
class MapBench {

  @Benchmark
  def andThenLift(s: MapBench.St, bh: Blackhole, k: McasImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % FlatMapBench.size
    val r: Axn[String] = s.rsWithAndThenLift(idx)
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def directMap(s: MapBench.St, bh: Blackhole, k: McasImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % FlatMapBench.size
    val r: Axn[String] = s.rsWithMap(idx)
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def monadMap(s: MapBench.St, bh: Blackhole, k: McasImplState): Unit = {
    val idx = Math.abs(k.nextInt()) % FlatMapBench.size
    val r: Axn[String] = s.rsWithMonadMap(idx)
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }
}

object MapBench {

  final val size = 8
  final val n = 16

  sealed abstract class OpType extends Product with Serializable
  final case object Map extends OpType
  final case object AndThenLift extends OpType
  final case object MonadMap extends OpType

  @State(Scope.Benchmark)
  class St {

    private[this] val dummy: Function1[String, String] = { s =>
      s.reverse
    }

    private[this] val refs = List.fill(size) {
      Ref.unsafe[String](ThreadLocalRandom.current().nextInt().toString)
    }

    private[this] val addXs: List[Axn[String]] =
      refs.map(_.updateAndGet(_.substring(1) + "x"))

    private[this] val addYs: List[Axn[String]] =
      refs.map(_.updateAndGet(_.substring(1) + "y"))

    private[this] final def mkReactions(opType: OpType): List[Axn[String]] = {
      List.tabulate(size) { idx =>
        val idx2 = (idx + 1) % size
        buildReaction(n, first = addXs(idx), last = addYs(idx2), opType = opType)
      }
    }

    val rsWithMap: List[Axn[String]] =
      mkReactions(opType = Map)

    val rsWithAndThenLift: List[Axn[String]] =
      mkReactions(opType = AndThenLift)

    val rsWithMonadMap: List[Axn[String]] =
      mkReactions(opType = MonadMap)

    private[this] final def buildReaction(n: Int, first: Axn[String], last: Axn[String], opType: OpType): Axn[String] = {
      def go(n: Int, acc: Axn[String]): Axn[String] = {
        if (n < 1) {
          acc
        } else {
          val newAcc = opType match {
            case Map =>
              acc.map(dummy)
            case AndThenLift =>
              acc >>> Rxn.lift(dummy)
            case MonadMap =>
              monadMap(acc, dummy)(Rxn.monadInstance)
          }
          go(n - 1, newAcc)
        }
      }

      go(n, first) *> last
    }

    private[this] final def monadMap[F[_], A, B](fa: F[A], f: A => B)(implicit F: Monad[F]): F[B] = {
      F.map(fa)(f)
    }
  }
}
