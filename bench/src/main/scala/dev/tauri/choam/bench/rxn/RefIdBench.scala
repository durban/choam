/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import RefIdBench._

@Fork(3)
@Threads(4)
class RefIdBench {

  @Benchmark
  def createPadded(): Ref[String] = {
    Ref.unsafePadded("")
  }

  @Benchmark
  def createUnpadded(): Ref[String] = {
    Ref.unsafeUnpadded("")
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
  class PaddedSt {
    val refs = Array.fill(N)(Ref.unsafePadded(""))
  }

  @State(Scope.Thread)
  class UnpaddedSt {
    val refs = Array.fill(N)(Ref.unsafeUnpadded(""))
  }
}
