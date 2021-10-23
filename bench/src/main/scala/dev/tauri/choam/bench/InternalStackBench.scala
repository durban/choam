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
import org.openjdk.jmh.infra.Blackhole

@Fork(2)
class InternalStackBench {

  import InternalStackBench._

  final val N = 512

  @Benchmark
  def byteStack(s: St, bh: Blackhole): Unit = {
    s.byteStack = new ByteStack(initSize = 8)
    var i = 0
    while (i < N) {
      s.byteStack.push(42)
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(s.byteStack.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      s.byteStack.push(42)
      i += 1
    }
  }

  @Benchmark
  def objStack(s: St, bh: Blackhole): Unit = {
    s.objStack = new ObjStack[String](initSize = 8)
    var i = 0
    while (i < N) {
      s.objStack.push("test")
      i += 1
    }
    i = 0
    while (i < N) {
      bh.consume(s.objStack.pop())
      i += 1
    }
    i = 0
    while (i < N) {
      s.objStack.push("test")
      i += 1
    }
  }
}

private object InternalStackBench {

  @State(Scope.Benchmark)
  class St {
    final var byteStack: ByteStack = null
    final var objStack: ObjStack[String] = null
  }
}
