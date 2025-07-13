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

import util._
import core.{ Rxn, Ref }

@Fork(3)
@Threads(1)
class RefSetBench {

  @Benchmark
  def getAndSetProvideVoid(s: RefSetBench.St, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val r: Rxn[Unit] = s.getAndSetVoid(idx)(rnd.nextString())
    r.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def set1(s: RefSetBench.St, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val r: Rxn[Unit] = s.refs(idx).set1(rnd.nextString())
    r.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def modify(s: RefSetBench.St, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val str = rnd.nextString()
    val r: Rxn[Unit] = s.refs(idx).modify { _ => (str, ()) }
    r.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def update1(s: RefSetBench.St, k: McasImplState, rnd: RandomState): Unit = {
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val str = rnd.nextString()
    val r: Rxn[Unit] = s.refs(idx).update1(_ => str)
    r.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def _imperativeDirect(s: RefSetBench.St, k: UnsafeApiState, rnd: RandomState): Unit = {
    import k.api.atomically
    import unsafe.writeRef
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val ref = s.refs(idx)
    val str = rnd.nextString()
    atomically { implicit ir =>
      writeRef(ref, str)
    }
  }

  @Benchmark
  def _imperativeSyntax(s: RefSetBench.St, k: UnsafeApiState, rnd: RandomState): Unit = {
    import k.api.atomically
    import unsafe.RefSyntax
    val idx = Math.abs(rnd.nextInt()) % RefSetBench.size
    val ref = s.refs(idx)
    val str = rnd.nextString()
    atomically { implicit ir =>
      ref.value = str
    }
  }
}

object RefSetBench {

  final val size = 8

  @State(Scope.Benchmark)
  class St extends McasImplStateBase {

    val refs: Array[Ref[String]] = Array.fill(size) {
      Ref.unsafePadded[String](ThreadLocalRandom.current().nextInt().toString, this.mcasImpl.currentContext().refIdGen)
    }

    val getAndSetVoid: Array[String => Rxn[Unit]] = refs.map { ref => { s => ref.getAndSet(s).void } }
  }
}
