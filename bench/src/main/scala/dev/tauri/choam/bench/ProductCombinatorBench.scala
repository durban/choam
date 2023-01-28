/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
import mcas.bench.Reset

@Fork(2)
class ProductCombinatorBench {

  import ProductCombinatorBench._

  @Benchmark
  def productDummy(s: DummyProduct, k: McasImplState): Unit = {
    s.prod.unsafePerform((), k.mcasImpl)
  }

  @Benchmark
  def productCAS(s: CASProduct, k: McasImplState): Unit = {
    s.prod.unsafePerform((), k.mcasImpl)
    s.reset.reset()
  }
}

object ProductCombinatorBench {

  @State(Scope.Thread)
  class DummyProduct extends ChoiceCombinatorBench.BaseState {

    var prod: Axn[Unit] = _

    @Setup
    def setup(): Unit = {
      this.prod = (1 to size).foldLeft[Axn[Unit]](Rxn.ret(())) { (r, idx) =>
        (r * Rxn.lift[String, String](_ + idx.toString).provide("foo")).void
      }
    }
  }

  @State(Scope.Thread)
  class CASProduct extends ChoiceCombinatorBench.BaseState {

    var prod: Axn[Unit] = _

    private[this] var refs: Array[Ref[String]] = _

    var reset: Reset[String] = _

    @Setup
    def setup(): Unit = {
      this.refs = Array.fill(size)(Ref.unsafe("foo"))
      this.reset = new Reset("foo", ArraySeq.unsafeWrapArray(this.refs): _*)
      this.prod = (0 until size).foldLeft[Axn[Unit]](Rxn.unit) { (r, idx) =>
        (r * refs(idx).unsafeCas("foo", "bar")).void
      }
    }
  }
}
