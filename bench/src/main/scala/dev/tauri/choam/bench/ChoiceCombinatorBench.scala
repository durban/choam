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

import mcas.bench.Reset
import util._

@Fork(3)
class ChoiceCombinatorBench {

  import ChoiceCombinatorBench._

  @Benchmark
  def choiceCAS(s: CASChoice, k: KCASImplState): Unit = {
    doChoiceCAS(s, k)
    s.reset.reset()
  }

  final def doChoiceCAS(s: CASChoice, k: KCASImplState): Unit = {
    s.choice.unsafePerform((), k.kcasImpl)
  }
}

object ChoiceCombinatorBench {

  @State(Scope.Thread)
  abstract class BaseState {
    @Param(Array("8", "16", "32"))
    var size: Int = _
  }

  @State(Scope.Thread)
  class CASChoice extends BaseState {

    private[bench] val ref =
      Ref.unsafe("foo")

    private[bench] var refs: Array[Ref[String]] =
      null

    val reset: Reset[String] =
      new Reset("foo", ref)

    var choice: Axn[Unit] = _

    def mkChoice(): Axn[Unit] = {
      val successfulCas = ref.unsafeCas("foo", "bar")
      val fails: Axn[Unit] = (0 until (size / 2)).foldLeft[Axn[Unit]](Rxn.unsafe.retry) { (acc, i) =>
        acc + (
          refs(2 * i).unsafeCas("foo", "bar") >>> refs((2 * i) + 1).unsafeCas("x", "-")
        )
      }
      fails + successfulCas
    }

    @Setup
    def setup(): Unit = {
      this.refs = Array.tabulate(size)(_ => Ref.unsafe("foo"))
      this.choice = mkChoice()
    }
  }
}
