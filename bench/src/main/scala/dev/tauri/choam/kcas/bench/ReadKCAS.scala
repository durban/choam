/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import dev.tauri.choam.bench.util.KCASImplState

/**
 * Benchmark for reading with different k-CAS implementations.
 *
 * One thread just reads 2 refs one-by-one, as fast as it can.
 * The other thread occasionally changes the values with a 2-CAS.
 */
@Fork(2)
class ReadKCAS {

  import ReadKCAS._

  @Benchmark
  @Group("ReadKCAS")
  def read(s: RefSt, t: ThSt, bh: Blackhole): Unit = {
    bh.consume(t.kcasImpl.read(s.ref1, t.kcasCtx))
    bh.consume(t.kcasImpl.read(s.ref2, t.kcasCtx))
  }

  @Benchmark
  @Group("ReadKCAS")
  def change(s: RefSt, t: ThSt): Unit = {
    val next1 = t.nextString()
    val next2 = t.nextString()
    val success = t.kcasImpl.tryPerform(
      t.kcasImpl.addCas(t.kcasImpl.addCas(t.kcasImpl.start(t.kcasCtx), s.ref1, t.last1, next1, t.kcasCtx), s.ref2, t.last2, next2, t.kcasCtx),
      t.kcasCtx
    )
    if (success) {
      t.last1 = next1
      t.last2 = next2
      // we only occasionally want to change values, so wait a bit:
      Blackhole.consumeCPU(ReadKCAS.tokens)
    } else {
      throw new Exception
    }
  }
}

object ReadKCAS {

  final val tokens = 4096L

  @State(Scope.Benchmark)
  class RefSt {
    val ref1 = Ref.mk("1")
    val ref2 = Ref.mk("2")
  }

  @State(Scope.Thread)
  class ThSt extends KCASImplState {
    var last1 = "1"
    var last2 = "2"
  }
}
