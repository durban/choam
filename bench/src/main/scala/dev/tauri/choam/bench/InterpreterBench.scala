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

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._
import InterpreterBench._

@Fork(2)
@Threads(2)
class InterpreterBench {

  // TODO: this benchmark doesn't include:
  // - invisible read
  // - delayComputed

  @Benchmark
  def internal(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    val x = k.nextInt()
    bh.consume(s.rxn.unsafePerform(x, k.kcasImpl))
  }

  @Benchmark
  def externalWithTag(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    val x = k.nextInt()
    bh.consume(Rxn.externalInterpreter(s.rxn, x, k.kcasImpl.currentContext()))
  }

  @Benchmark
  def rxnNew(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    val x = k.nextInt()
    bh.consume(RxnNew.interpreter(s.rxnNew, x, k.kcasImpl.currentContext()))
  }
}

object InterpreterBench {

  final val N = 4

  @State(Scope.Benchmark)
  class St {
    private[this] val ref1s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("1") }

    private[this] val ref2s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("2") }

    private[this] val ref3s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("3") }

    private[this] val ref4s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("4") }

    private[this] val ref5s: Array[Ref[String]] =
      Array.fill(N) { Ref.unsafePadded("5") }

    private[this] val cnt: Ref[Long] =
      Ref.unsafePadded(0L)

    private[InterpreterBench] val rxnNew: RxnNew[Int, String] = {
      val rxn1 = (0 until N).map { idx =>
        mkRxnNew1(ref1s(idx), ref2s(idx), ref3s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn2 = (0 until N).map { idx =>
        mkRxnNew2(ref4s(idx), ref5s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      rxn1 *> rxn2
    }

    private[InterpreterBench] val rxn: Rxn[Int, String] = {
      val rxn1 = (0 until N).map { idx =>
        mkRxn1(ref1s(idx), ref2s(idx), ref3s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      val rxn2 = (0 until N).map { idx =>
        mkRxn2(ref4s(idx), ref5s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }

      rxn1 *> rxn2
    }

    private[this] def mkRxnNew1(ref1: Ref[String], ref2: Ref[String], ref3: Ref[String]): RxnNew[Int, String] = {
      RxnNew.computed { (i: Int) =>
        (if ((i % 2) == 0) {
          RxnNew.ref.getAndUpdate(ref1)(ov => (ov.toInt + i).toString) >>> RxnNew.ref.getAndSet(ref2)
        } else {
          RxnNew.ref.getAndUpdate(ref2)(ov => (ov.toInt - i).toString) >>> RxnNew.ref.getAndSet(ref1)
        }) >>> RxnNew.computed { (s: String) =>
          (RxnNew.unsafe.cas(ref3, s, (s.toInt + 1).toString) + RxnNew.ref.update(ref3)(_.length.toString)).as(s)
        }
      }.postCommit(RxnNew.ref.update(cnt)(_ + 1L))
    }

    private[this] def mkRxn1(ref1: Ref[String], ref2: Ref[String], ref3: Ref[String]): Int =#> String = {
      Rxn.computed { (i: Int) =>
        (if ((i % 2) == 0) {
          ref1.getAndUpdate(ov => (ov.toInt + i).toString) >>> ref2.getAndSet
        } else {
          ref2.getAndUpdate(ov => (ov.toInt - i).toString) >>> ref1.getAndSet
        }) >>> Rxn.computed { (s: String) =>
          (ref3.unsafeCas(s, (s.toInt + 1).toString) + ref3.update(_.length.toString)).as(s)
        }
      }.postCommit(cnt.update(_ + 1L))
    }

    private[this] def mkRxnNew2(ref4: Ref[String], ref5: Ref[String]): RxnNew[Int, String] = {
      RxnNew.ref.updWith[String, Int, String](ref4) { (ov4, i) =>
        if ((i % 2) == 0) RxnNew.ref.getAndUpdate(ref5)(_ => ov4).map(s => (s, s))
        else RxnNew.unsafe.retry
      } + RxnNew.ref.getAndUpdate(ref4)(ov4 => (ov4.toInt + 1).toString)
    }

    private[this] def mkRxn2(ref4: Ref[String], ref5: Ref[String]): Int =#> String = {
      ref4.updWith[Int, String] { (ov4, i) =>
        if ((i % 2) == 0) ref5.getAndUpdate(_ => ov4).map(s => (s, s))
        else Rxn.unsafe.retry
      } + ref4.getAndUpdate(ov4 => (ov4.toInt + 1).toString)
    }
  }
}
