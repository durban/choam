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

import util.McasImplState
import RefIdBench._
import dev.tauri.choam.bench.util.McasImplStateBase

@Fork(3)
@Threads(4)
class RefIdBench {

  @Benchmark
  def createPaddedCtx(k: McasImplState): Ref[String] = {
    Ref.unsafePadded("", k.mcasCtx.refIdGen)
  }

  @Benchmark
  def createUnpaddedCtx(k: McasImplState): Ref[String] = {
    Ref.unsafeUnpadded("", k.mcasCtx.refIdGen)
  }

  @Benchmark
  def comparePadded(st: PaddedSt): Int = {
    compare(st.refs)
  }

  @Benchmark
  def compareUnpadded(st: UnpaddedSt): Int = {
    compare(st.refs)
  }

  private[this] final def compare(refs: Array[Ref[String]]): Int = {
    val tlr = ThreadLocalRandom.current()
    val a = refs(tlr.nextInt(N))
    val b = refs(tlr.nextInt(N))
    cmpRefs(a, b)
  }

  private[this] final def cmpRefs(a: Ref[String], b: Ref[String]): Int = {
    internal.mcas.MemoryLocation.globalCompare(a.loc, b.loc)
  }
}

object RefIdBench {

  final val N = 1024

  @State(Scope.Thread)
  class PaddedSt extends McasImplStateBase {
    val refs = Array.fill(N)(Ref.unsafePadded("", this.mcasImpl.currentContext().refIdGen))
  }

  @State(Scope.Thread)
  class UnpaddedSt extends McasImplStateBase {
    val refs = Array.fill(N)(Ref.unsafeUnpadded("", this.mcasImpl.currentContext().refIdGen))
  }
}
