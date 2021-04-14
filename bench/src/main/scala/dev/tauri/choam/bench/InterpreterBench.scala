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

  @Benchmark
  def internal(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    val x = k.nextInt()
    bh.consume(s.rxn1.unsafePerform(x, k.kcasImpl))
  }

  @Benchmark
  def externalWithTag(s: St, bh: Blackhole, k: KCASImplState): Unit = {
    val x = k.nextInt()
    bh.consume(React.externalInterpreter(s.rxn1, x, k.kcasImpl.currentContext()))
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
      Array.fill(N) { Ref.unsafePadded("2") }

    private[this] val cnt: Ref[Long] =
      Ref.unsafePadded(0L)

    private[InterpreterBench] val rxn1: Rxn[Int, String] = {
      (0 until N).map { idx =>
        mkRxn1(ref1s(idx), ref2s(idx), ref3s(idx))
      }.reduce { (x, y) => (x * y).map(_._2) }
    }

    private[this] def mkRxn1(ref1: Ref[String], ref2: Ref[String], ref3: Ref[String]): Rxn[Int, String] = {
      React.computed { (i: Int) =>
        (if ((i % 2) == 0) {
          ref1.getAndUpdate(ov => (ov.toInt + i).toString) >>> ref2.getAndSet
        } else {
          ref2.getAndUpdate(ov => (ov.toInt - i).toString) >>> ref1.getAndSet
        }) >>> React.computed { (s: String) =>
          (ref3.unsafeCas(s, (s.toInt + 1).toString) + ref3.update(_.length.toString)).as(s)
        }
      }.postCommit(cnt.update(_ + 1L))
    }
  }
}
