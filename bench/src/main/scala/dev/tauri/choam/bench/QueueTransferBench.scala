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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._

@Fork(2)
class QueueTransferBench {

  import QueueTransferBench._

  final val waitTime = 128L

  @Benchmark
  def michaelScottQueue(s: MsSt, bh: Blackhole, ct: KCASImplState): Unit = {
    import ct.kcasImpl
    bh.consume(s.michaelScottQueue1.enqueue.unsafePerform(ct.nextString()))
    bh.consume(s.transfer.unsafeRun())
    if (s.michaelScottQueue2.tryDeque.unsafeRun() eq None) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def lockedQueue(s: LockedSt, bh: Blackhole, ct: RandomState): Unit = {
    bh.consume(s.lockedQueue1.enqueue(ct.nextString()))

    s.lockedQueue1.lock.lock()
    s.lockedQueue2.lock.lock()
    try {
      val item = s.lockedQueue1.unlockedTryDequeue().get
      bh.consume(s.lockedQueue2.unlockedEnqueue(item))
    } finally {
      s.lockedQueue1.lock.unlock()
      s.lockedQueue2.lock.unlock()
    }

    if (s.lockedQueue2.tryDequeue() eq None) throw Errors.EmptyQueue

    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def stmQueue(s: StmSt, bh: Blackhole, ct: RandomState): Unit = {
    import scala.concurrent.stm._
    bh.consume(s.stmQueue1.enqueue(ct.nextString()))
    bh.consume(atomic { implicit txn =>
      val item = s.stmQueue1.tryDequeue().get
      s.stmQueue2.enqueue(item)
    })
    if (s.stmQueue2.tryDequeue() eq None) throw Errors.EmptyQueue
    Blackhole.consumeCPU(waitTime)
  }
}

object QueueTransferBench {

  @State(Scope.Benchmark)
  class MsSt {
    val michaelScottQueue1 = new MichaelScottQueue[String](Prefill.prefill())
    val michaelScottQueue2 = new MichaelScottQueue[String](Prefill.prefill())
    val transfer = michaelScottQueue1.tryDeque.map(_.get) >>> michaelScottQueue2.enqueue
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val lockedQueue1 = new LockedQueue[String](Prefill.prefill())
    val lockedQueue2 = new LockedQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val stmQueue1 = new StmQueue[String](Prefill.prefill())
    val stmQueue2 = new StmQueue[String](Prefill.prefill())
  }
}
