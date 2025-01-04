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
package datastruct

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._
import data.{ Counter, CounterHelper }

@Fork(2)
class CounterBench {

  import CounterBench._

  final val waitTime = 128L

  @Benchmark
  def reference(s: ReferenceSt, t: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.referenceCtr.add(t.nextLong()))
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def locked(s: LockedSt, t: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.lockedCtr.add(t.nextLong()))
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def react(s: ReactSt, k: McasImplState, bh: Blackhole): Unit = {
    bh.consume(s.reactCtr.add.unsafePerform(k.nextLong(), k.mcasImpl))
    Blackhole.consumeCPU(waitTime)
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
  class LockedSt {
    val lockedCtr: LockedCounter = {
      val ctr = new LockedCounter
      val init = java.util.concurrent.ThreadLocalRandom.current().nextLong()
      ctr.add(init) : Unit
      ctr
    }
  }

  @State(Scope.Benchmark)
  class ReactSt {
    val reactCtr: Counter = {
      val init = java.util.concurrent.ThreadLocalRandom.current().nextLong()
      CounterHelper.unsafe(initial = init)
    }
  }
}

@Fork(2)
class CounterBenchN {

  import CounterBenchN._

  final val waitTime = 128L

  @Benchmark
  def lockedN(s: LockedStN, t: RandomState, bh: Blackhole): Unit = {
    bh.consume(s.lockedCtrN.add(t.nextLong()))
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def reactN(s: ReactStN, k: McasImplState, bh: Blackhole): Unit = {
    bh.consume(s.r.unsafePerform(k.nextLong(), k.mcasImpl))
    Blackhole.consumeCPU(waitTime)
  }
}

object CounterBenchN {

  @State(Scope.Benchmark)
  class LockedStN {

    private[this] final val n = 8

    @volatile
    var lockedCtrN: LockedCounterN = _

    @Setup
    def setup(): Unit = {
      val ctr = new LockedCounterN(n)
      val init = java.util.concurrent.ThreadLocalRandom.current().nextLong()
      ctr.add(init)
      lockedCtrN = ctr
    }
  }

  @State(Scope.Benchmark)
  class ReactStN {

    private[this] final val n = 8

    private[this] var ctrs: Array[Counter] = _

    @volatile
    var r: Rxn[Long, Unit] = _

    @Setup
    def setup(): Unit = {
      val init = java.util.concurrent.ThreadLocalRandom.current().nextLong()
      ctrs = Array.fill(n) {
        CounterHelper.unsafe(initial = init)
      }
      r = ctrs.map(_.add.as(())).reduceLeft { (a, b) => (a * b).as(()) }
    }
  }
}
