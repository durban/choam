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

import org.openjdk.jmh.annotations._

import internal.mcas.RefIdGen

import util.McasImplState
import RefIdGenBench.ArraySt

@Fork(3)
@Threads(2) // 4, 6, 8, ...
class RefIdGenBench {

  @Benchmark
  def global(): Long = {
    RefIdGen.global.nextId()
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
  def createThreadLocal(): Long = {
    RefIdGen.global.newThreadLocal().nextId()
  }

  // TODO: All the array* methods below never generate
  // TODO: "regular" IDs, so the block is always empty,
  // TODO: so they always fall back to global. So we're
  // TODO: not measuring the correct thing.

  @Benchmark
  def arrayGlobal(s: ArraySt): Long = {
    RefIdGen.global.nextArrayIdBase(s.size)
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
  def arrayCreateThreadLocal(s: ArraySt): Long = {
    RefIdGen.global.newThreadLocal().nextArrayIdBase(s.size)
  }
}

object RefIdGenBench {

  @State(Scope.Thread)
  class ArraySt {
    @Param(Array("2", "16", "128"))
    var size: Int = -1
  }
}
