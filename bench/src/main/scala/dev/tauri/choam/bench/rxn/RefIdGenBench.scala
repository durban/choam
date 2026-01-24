/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import org.openjdk.jmh.annotations._

import internal.mcas.{ RefIdGen, GlobalRefIdGen }

import util.McasImplState
import RefIdGenBench.{ ArraySt, GlobalSt }

@Fork(3)
@Threads(2) // 4, 6, 8, ...
class RefIdGenBench {

  @Benchmark
  def global(g: GlobalSt): Long = {
    g.global.nextId()
  }

  @Benchmark
  def existingThreadContext(k: McasImplState): Long = {
    k.mcasCtx.refIdGen.nextId()
  }

  @Benchmark
  def retrieveThreadContext(k: McasImplState): Long = {
    k.mcasImpl.currentContext().refIdGen.nextId()
  }

  @Benchmark
  def createThreadLocal(g: GlobalSt): Long = {
    g.global.newThreadLocal(isVirtualThread = false).nextId()
  }

  @Benchmark
  def arrayGlobal(s: ArraySt, g: GlobalSt): Long = {
    g.global.nextArrayIdBase(s.size)
  }

  @Benchmark
  def arrayExistingThreadContext(s: ArraySt, k: McasImplState): Long = {
    k.mcasCtx.refIdGen.nextArrayIdBase(s.size)
  }

  @Benchmark
  def arrayRetrieveThreadContext(s: ArraySt, k: McasImplState): Long = {
    k.mcasImpl.currentContext().refIdGen.nextArrayIdBase(s.size)
  }

  @Benchmark
  def arrayCreateThreadLocal(s: ArraySt, g: GlobalSt): Long = {
    g.global.newThreadLocal(isVirtualThread = false).nextArrayIdBase(s.size)
  }
}

object RefIdGenBench {

  @State(Scope.Thread)
  class ArraySt {
    @Param(Array("2", "16", "1024"))
    var size: Int = -1
  }

  @State(Scope.Benchmark)
  class GlobalSt {
    val global: GlobalRefIdGen = RefIdGen.newGlobal_public()
  }
}
