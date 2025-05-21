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

import util._
import core.{ Axn, Ref }

@Fork(3)
@Threads(2)
class RefSetBench {

  @Benchmark
  def baseline(s: RefSetBench.St, bh: Blackhole, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    bh.consume(s.refs(idx))
    bh.consume(rnd.nextString())
  }

  @Benchmark
  def getAndSetProvideVoid(s: RefSetBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val r: Axn[Unit] = s.refs(idx).getAndSet.provide(rnd.nextString()).void
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def setProvide(s: RefSetBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val r: Axn[Unit] = s.refs(idx).set0.provide(rnd.nextString())
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def upd(s: RefSetBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val str = rnd.nextString()
    val r: Axn[Unit] = s.refs(idx).upd[Any, Unit] { (_, _) => (str, ()) }
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }

  @Benchmark
  def update(s: RefSetBench.St, bh: Blackhole, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val str = rnd.nextString()
    val r: Axn[Unit] = s.refs(idx).update(_ => str)
    bh.consume(r.unsafePerform((), k.mcasImpl))
  }
}

object RefSetBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {
    val refs: Array[Ref[String]] = Array.fill(size) {
      Ref.unsafePadded[String](ThreadLocalRandom.current().nextInt().toString, this.mcasImpl.currentContext().refIdGen)
    }
  }
}
