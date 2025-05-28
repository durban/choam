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

import org.openjdk.jmh.annotations._

import core.{ =#>, Rxn, Axn, Ref }
import util._
import InterpreterBench._

@Fork(3)
@Threads(2)
class InterpreterBench {

  // TODO: this benchmark doesn't include:
  // TODO: - direct read
  // TODO: - creating new Refs
  // TODO: - read-only Rxn

  @Benchmark
  def rxnNew(s: St, k: McasImplState, rnd: RandomState): String = {
    val x = rnd.nextInt()
    s.rxn.unsafePerform(x, k.mcasImpl)
  }

  @Benchmark
  def rxnNewDisjoint(s: DisjointSt, k: McasImplState, rnd: RandomState): String = {
    val x = rnd.nextInt()
    s.rxn.unsafePerform(x, k.mcasImpl)
  }

  @Benchmark
  def readHeavy(s: St, k: McasImplState, rnd: RandomState): String = {
    val x = rnd.nextInt()
    s.rhRxn.unsafePerform(x, k.mcasImpl)
  }

  @Benchmark
  def readHeavyDisjoint(s: DisjointSt, k: McasImplState, rnd: RandomState): String = {
    val x = rnd.nextInt()
    s.rhRxn.unsafePerform(x, k.mcasImpl)
  }
}

object InterpreterBench {

  final val N = 4

  abstract class BaseSt extends McasImplStateBase {

    private[this] val ref1s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("1", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref2s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("2", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref3s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("3", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref4s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("4", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref5s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("5", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref6s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("6", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref7s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("7", this.mcasImpl.currentContext().refIdGen) }

    private[this] val ref8s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("8", this.mcasImpl.currentContext().refIdGen) }

    private[this] val cnt: Ref[Long] =
      Ref.unsafePadded(0L, this.mcasImpl.currentContext().refIdGen)

    private[InterpreterBench] val rxn: Rxn[Int, String] = {
      val rxn1 = (0 until N).map { idx =>
        mkRxn1(ref1s(idx), ref2s(idx), ref3s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn2 = (0 until N).map { idx =>
        mkRxn2(ref4s(idx), ref5s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn3 = (0 until N).map { idx =>
        mkRxn3(ref6s(idx), ref7s(idx), ref8s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      rxn1 *> rxn3 *> rxn2
    }

    private[this] def mkRxn1(ref1: Ref[String], ref2: Ref[String], ref3: Ref[String]): Int =#> String = {
      Rxn.computed { (i: Int) =>
        (if ((i % 2) == 0) {
          ref1.getAndUpdate(ov => (ov.toInt + i).toString) >>> ref2.getAndSet
        } else {
          ref2.getAndUpdate(ov => (ov.toInt - i).toString) >>> ref1.getAndSet
        }) >>> Rxn.computed { (s: String) =>
          (Rxn.unsafe.cas(ref3, s, (s.toInt + 1).toString) + ref3.update(_.length.toString)).as(s)
        }
      }.postCommit(cnt.update(_ + 1L))
    }

    private[this] def mkRxn2(ref4: Ref[String], ref5: Ref[String]): Int =#> String = {
      ref4.updWith[Int, String] { (ov4, i) =>
        if ((i % 2) == 0) ref5.getAndUpdate(_ => ov4).map(s => (s, s))
        else Rxn.unsafe.retry
      } + ref4.getAndUpdate(ov4 => (ov4.toInt + 1).toString)
    }

    private[this] def mkRxn3(ref6: Ref[String], ref7: Ref[String], ref8: Ref[String]): Axn[Unit] = {
      def modOrRetry(ref: Ref[String]): Axn[Unit] = {
        ref.updateWith { s =>
          if ((s.toInt % 2) == 0) Rxn.pure(s.##.toString)
          else Rxn.unsafe.retry
        }
      }
      modOrRetry(ref6) + modOrRetry(ref7) + ref8.update { s =>
        s.##.toString
      }
    }

    private[InterpreterBench] val rhRxn: Rxn[Int, String] = {
      val rxn1 = (0 until N).map { idx =>
        mkRhRxn1(ref1s(idx), ref2s(idx), ref3s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn2 = (0 until N).map { idx =>
        mkRhRxn2(ref4s(idx), ref5s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn3 = (0 until N).map { idx =>
        mkRhRxn3(ref6s(idx), ref7s(idx), ref8s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      rxn1 *> rxn3 *> rxn2
    }

    private[this] def mkRhRxn1(ref1: Ref[String], ref2: Ref[String], ref3: Ref[String]): Int =#> String = {
      Rxn.computed { (i: Int) =>
        (if ((i % 2) == 0) ref1.get else ref2.get) >>> ref3.updateAndGet(_.length.toString)
      }
    }

    private[this] def mkRhRxn2(ref4: Ref[String], ref5: Ref[String]): Int =#> String = {
      ref4.upd[Int, Int] { (ov4, i) =>
        (ov4, i + 1)
      }.flatMapF { i =>
        if ((i % 2) == 0) ref5.get else ref5.getAndSet.provide(i.toString)
      }
    }

    private[this] def mkRhRxn3(ref6: Ref[String], ref7: Ref[String], ref8: Ref[String]): Axn[Unit] = {
      (ref6.get *> ref7.get *> ref8.get).void
    }
  }

  @State(Scope.Benchmark)
  class St extends BaseSt

  @State(Scope.Thread)
  class DisjointSt extends BaseSt
}
