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

import cats.Monad

import org.openjdk.jmh.annotations._

import util._

/** Compares the performance of possible `map` and `map2` implementations */
@Fork(3)
@Threads(2)
class MapBench {

  @Benchmark
  def map_andThenLift(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rsWithAndThenLift(idx)
    r.unsafePerform((), k.mcasImpl)
  }

  @Benchmark
  def map_directMap(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rsWithMapDirect(idx)
    r.unsafePerform((), k.mcasImpl)
  }

  @Benchmark
  def map_monadMap(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rsWithMonadMap(idx)
    r.unsafePerform((), k.mcasImpl)
  }

  @Benchmark
  def map2_andAlsoTupled(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rs2WithAndAlsoTupled(idx)
    r.unsafePerform(null, k.mcasImpl)
  }

  @Benchmark
  def map2_flatMapMap(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rs2WithFlatMapMap(idx)
    r.unsafePerform(null, k.mcasImpl)
  }

  @Benchmark
  def map2_primitive(s: MapBench.St, k: McasImplState, rnd: RandomState): String = {
    val idx = Math.abs(rnd.nextInt()) % MapBench.size
    val r: Axn[String] = s.rs2WithPrimitive(idx)
    r.unsafePerform(null, k.mcasImpl)
  }
}

object MapBench {

  final val size = 8
  final val n = 16

  sealed abstract class OpType extends Product with Serializable
  final case object MapDirect extends OpType
  final case object AndThenLift extends OpType
  final case object MonadMap extends OpType
  final case object Map2AndAlsoTupled extends OpType
  final case object Map2FlatMapMap extends OpType
  final case object Map2Primitive extends OpType

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {

    private[this] val dummy: Function1[String, String] = { s =>
      s.reverse
    }

    private[this] val take1: Function2[String, String, String] = { (s1, _) =>
      s1
    }

    private[this] val refs = List.fill(size) {
      Ref.unsafePadded[String](
        ThreadLocalRandom.current().nextInt().toString,
        this.mcasImpl.currentContext().refIdGen,
      )
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

    val rsWithMapDirect: List[Axn[String]] =
      mkReactions(opType = MapDirect)

    val rsWithAndThenLift: List[Axn[String]] =
      mkReactions(opType = AndThenLift)

    val rsWithMonadMap: List[Axn[String]] =
      mkReactions(opType = MonadMap)

    val rs2WithAndAlsoTupled: List[Axn[String]] =
      mkReactions(opType = Map2AndAlsoTupled)

    val rs2WithFlatMapMap: List[Axn[String]] =
      mkReactions(opType = Map2FlatMapMap)

    val rs2WithPrimitive: List[Axn[String]] =
      mkReactions(opType = Map2Primitive)

    private[this] final def buildReaction(n: Int, first: Axn[String], last: Axn[String], opType: OpType): Axn[String] = {
      def go(n: Int, acc: Axn[String]): Axn[String] = {
        if (n < 1) {
          acc
        } else {
          val newAcc = opType match {
            case MapDirect =>
              acc.map(dummy)
            case AndThenLift =>
              acc >>> Rxn.lift(dummy)
            case MonadMap =>
              monadMap(acc, dummy)(Rxn.monadInstance)
            case Map2AndAlsoTupled =>
              map2WithAndAlsoTupled(acc, last)(take1)
            case Map2FlatMapMap =>
              map2WithFlatMap(acc, last)(take1)
            case Map2Primitive =>
              map2WithPrimitive(acc, last)(take1)
          }
          go(n - 1, newAcc)
        }
      }

      go(n, first) *> last
    }

    private[this] final def monadMap[F[_], A, B](fa: F[A], f: A => B)(implicit F: Monad[F]): F[B] = {
      F.map(fa)(f)
    }

    private[this] final def map2WithAndAlsoTupled[X, A, B, Z](fa: Rxn[X, A], fb: Rxn[X, B])(f: (A, B) => Z): Rxn[X, Z] = {
      (fa * fb).map(f.tupled)
    }

    private[this] final def map2WithFlatMap[X, A, B, Z](fa: Rxn[X, A], fb: Rxn[X, B])(f: (A, B) => Z): Rxn[X, Z] = {
      fa.flatMap { a => fb.map { b => f(a, b) } }
    }

    private[this] final def map2WithPrimitive[X, A, B, Z](fa: Rxn[X, A], fb: Rxn[X, B])(f: (A, B) => Z): Rxn[X, Z] = {
      fa.map2(fb)(f)
    }
  }
}
