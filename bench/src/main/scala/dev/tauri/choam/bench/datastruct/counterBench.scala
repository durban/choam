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
package datastruct

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._
import data.Counter

@Fork(2)
@Threads(4)
class CounterBench {

  import CounterBench._

  @Benchmark
  def atomicReference(s: ReferenceSt, t: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.referenceCtr.add(t.nextLong()))
  }

  @Benchmark
  def rxnSimpleUnpadded(s: ReactSt, k: McasImplState): Unit = {
    s.rxnSimpleUnpadded.incr.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def rxnSimplePadded(s: ReactSt, k: McasImplState): Unit = {
    s.rxnSimplePadded.incr.unsafePerform(k.mcasImpl)
  }

  @Benchmark
  def rxnStripedPadded(s: ReactSt, k: McasImplState): Unit = {
    s.rxnStripedPadded.incr.unsafePerform(k.mcasImpl)
  }
}

object CounterBench {

  @State(Scope.Benchmark)
  class ReferenceSt {
    val referenceCtr: ReferenceCounter = {
      val ctr = new ReferenceCounter
      val init = java.util.concurrent.ThreadLocalRandom.current().nextLong()
      ctr.add(init) : Unit
      ctr
    }
  }

  @State(Scope.Benchmark)
  class ReactSt extends McasImplStateBase {
    val rxnSimpleUnpadded: Counter =
      Counter.simple(AllocationStrategy.Unpadded).unsafePerform(this.mcasImpl)
    val rxnSimplePadded: Counter =
      Counter.simple(AllocationStrategy.Padded).unsafePerform(this.mcasImpl)
    val rxnStripedPadded: Counter =
      Counter.striped(AllocationStrategy.Padded).unsafePerform(this.mcasImpl)
  }
}
