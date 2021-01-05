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

import util._

@Fork(2)
class QueueBench {

  import QueueBench._

  final val waitTime = 128L

  @Benchmark
  def michaelScottQueue(s: MsSt, bh: Blackhole, t: KCASImplState): Unit = {
    import t.kcasImpl
    bh.consume(s.michaelScottQueue.enqueue.unsafePerform(t.nextString()))
    Blackhole.consumeCPU(waitTime)
    if (s.michaelScottQueue.tryDeque.unsafeRun() eq None) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def lockedQueue(s: LockedSt, bh: Blackhole, t: RandomState): Unit = {
    bh.consume(s.lockedQueue.enqueue(t.nextString()))
    Blackhole.consumeCPU(waitTime)
    if (s.lockedQueue.tryDequeue() eq None) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def concurrentQueue(s: JdkSt, bh: Blackhole, t: RandomState): Unit = {
    bh.consume(s.concurrentQueue.offer(t.nextString()))
    Blackhole.consumeCPU(waitTime)
    if (s.concurrentQueue.poll() eq null) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def stmQueue(s: StmSt, bh: Blackhole, t: RandomState): Unit = {
    bh.consume(s.stmQueue.enqueue(t.nextString()))
    Blackhole.consumeCPU(waitTime)
    if (s.stmQueue.tryDequeue() eq None) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }
}

object QueueBench {

  @State(Scope.Benchmark)
  class MsSt {
    val michaelScottQueue = new MichaelScottQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val lockedQueue = new LockedQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class JdkSt {
    val concurrentQueue = new java.util.concurrent.ConcurrentLinkedQueue[String](Prefill.forJava())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val stmQueue = new StmQueue[String](Prefill.prefill())
  }
}
