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

import scala.collection.immutable.ArraySeq

import util._
import kcas._
import kcas.bench.Reset

@Fork(2)
class ProductCombinatorBench {

  import ProductCombinatorBench._

  @Benchmark
  def productDummy(s: DummyProduct, k: KCASImplState): Unit = {
    import k.kcasImpl
    s.prod.unsafeRun()
  }

  @Benchmark
  def productCAS(s: CASProduct, k: KCASImplState): Unit = {
    import k.kcasImpl
    s.prod.unsafeRun()
    s.reset.reset()
  }
}

object ProductCombinatorBench {

  @State(Scope.Thread)
  class DummyProduct extends ChoiceCombinatorBench.BaseState {

    var prod: React[Unit, Unit] = _

    @Setup
    def setup(): Unit = {
      this.prod = (1 to size).foldLeft[React[Unit, Unit]](React.ret(())) { (r, idx) =>
        (r * React.lift[String, String](_ + idx.toString).lmap[Unit](_ => "foo")).discard
      }
    }
  }

  @State(Scope.Thread)
  class CASProduct extends ChoiceCombinatorBench.BaseState {

    var prod: React[Unit, Unit] = _

    private[this] var refs: Array[Ref[String]] = _

    var reset: Reset[String] = _

    @Setup
    def setup(): Unit = {
      this.refs = Array.fill(size)(Ref.mk("foo"))
      this.reset = new Reset("foo", ArraySeq.unsafeWrapArray(this.refs): _*)
      this.prod = (0 until size).foldLeft[React[Unit, Unit]](React.ret(())) { (r, idx) =>
        (r * refs(idx).cas("foo", "bar")).discard
      }
    }
  }
}
