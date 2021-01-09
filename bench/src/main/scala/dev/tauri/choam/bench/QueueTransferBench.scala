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

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.github.timwspence.cats.stm.STM

import util._

@Fork(2)
class QueueTransferBench {

  import QueueTransferBench._

  final val waitTime = 128L
  final val size = 4096

  private def run(rt: IORuntime, task: IO[Unit], size: Int): Unit = {
    IO.asyncForIO.replicateA(size, task).unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  /** MS-Queues implemented with `React` */
  @Benchmark
  def michaelScottQueue(s: MsSt, ct: KCASImplState): Unit = {
    val tsk = IO { ct.nextString() }.flatMap { nextString =>
      s.michaelScottQueue1.enqueue[IO](nextString)(ct.reactive).flatMap { _ =>
        s.transfer.run[IO](ct.reactive).flatMap { _ =>
          s.michaelScottQueue2.tryDeque.run[IO](ct.reactive)
        }
      }
    }

    run(s.runtime, tsk.void, size = size)
  }

  /** Simple queues protected with reentrant locks */
  @Benchmark
  def lockedQueue(s: LockedSt, ct: RandomState): Unit = {
    val tsk = IO {
      s.lockedQueue1.enqueue(ct.nextString())

      s.lockedQueue1.lock.lock()
      s.lockedQueue2.lock.lock()
      try {
        val item = s.lockedQueue1.unlockedTryDequeue().get
        s.lockedQueue2.unlockedEnqueue(item)
      } finally {
        s.lockedQueue1.lock.unlock()
        s.lockedQueue2.lock.unlock()
      }

      s.lockedQueue2.tryDequeue()
    }

    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queues implemented with scala-stm */
  @Benchmark
  def stmQueue(s: StmSt, ct: RandomState): Unit = {
    import scala.concurrent.stm._
    val tsk = IO {
      s.stmQueue1.enqueue(ct.nextString())
      atomic { implicit txn =>
        val item = s.stmQueue1.tryDequeue().get
        s.stmQueue2.enqueue(item)
      }
      s.stmQueue2.tryDequeue()
    }

    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queues implemented with cats-stm */
  @Benchmark
  def stmQueueC(s: StmCSt, t: RandomState): Unit = {
    val tsk = IO { t.nextString() }.flatMap { nextString =>
      s.s.commit(s.stmQueue1.enqueue(nextString)).flatMap { _ =>
        s.s.commit {
          s.stmQueue1.tryDequeue.flatMap { e =>
            s.stmQueue2.enqueue(e.get)
          }
        }.flatMap { _ =>
          s.s.commit(s.stmQueue2.tryDequeue)
        }
      }
    }

    run(s.runtime, tsk.void, size = size)
  }
}

object QueueTransferBench {

  @State(Scope.Benchmark)
  class MsSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val michaelScottQueue1 = new MichaelScottQueue[String](Prefill.prefill())
    val michaelScottQueue2 = new MichaelScottQueue[String](Prefill.prefill())
    val transfer = michaelScottQueue1.tryDeque.map(_.get) >>> michaelScottQueue2.enqueue
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val lockedQueue1 = new LockedQueue[String](Prefill.prefill())
    val lockedQueue2 = new LockedQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val stmQueue1 = new StmQueue[String](Prefill.prefill())
    val stmQueue2 = new StmQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmCSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val s = STM.runtime[IO].unsafeRunSync()(runtime)
    val qu = StmQueueCLike[STM, IO](s)
    val stmQueue1 = s.commit(StmQueueC.make(s)(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
    val stmQueue2 = s.commit(StmQueueC.make(s)(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
  }
}
