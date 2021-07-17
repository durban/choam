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

import kcas.bench.Reset
import util._

@Fork(3)
class ChoiceCombinatorBench {

  import ChoiceCombinatorBench._

  @Benchmark
  def choiceDummy(s: DummyChoice, k: KCASImplState): Unit = {
    s.choice.unsafePerform((), k.kcasImpl)
    s.reset.unsafePerform((), k.kcasImpl)
  }

  @Benchmark
  def choiceCAS(s: CASChoice, k: KCASImplState): Unit = {
    s.choice.unsafePerform((), k.kcasImpl)
    s.reset.reset()
  }
}

object ChoiceCombinatorBench {

  @State(Scope.Thread)
  abstract class BaseState {
    @Param(Array("8", "16", "32"))
    var size: Int = _
  }

  @State(Scope.Thread)
  class DummyChoice extends BaseState {

    private[this] val ref =
      Ref.unsafe("foo")

    val reset: Axn[Unit] =
      ref.update(_ => "foo")

    var choice: Axn[Unit] = _

    def mkChoice(n: Int): Axn[Unit] = {
      val successfulCas = ref.unsafeCas("foo", "bar")
      val fails = (1 to n).foldLeft[Axn[Unit]](Rxn.unsafe.retry) { (r, _) =>
        r + Rxn.unsafe.retry
      }
      fails + successfulCas
    }

    @Setup
    def setup(): Unit = {
      this.choice = mkChoice(size)
    }
  }

  @State(Scope.Thread)
  class CASChoice extends BaseState {

    private[this] val ref =
      Ref.unsafe("foo")

    private[this] var refs: Array[Ref[String]] =
      null

    val reset: Reset[String] =
      new Reset("foo", ref)

    var choice: Axn[Unit] = _

    def mkChoice(): Axn[Unit] = {
      val successfulCas = ref.unsafeCas("foo", "bar")
      val fails = refs.foldLeft[Axn[Unit]](Rxn.unsafe.retry) { (r, ref) =>
        r + ref.unsafeCas("invalid", "dontcare")
      }
      fails + successfulCas
    }

    @Setup
    def setup(): Unit = {
      this.refs = Array.tabulate(size)(i => Ref.unsafe(i.toString))
      this.choice = mkChoice()
    }
  }
}
