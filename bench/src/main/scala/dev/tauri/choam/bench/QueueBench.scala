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
class QueueBench {

  import QueueBench._

  final val waitTime = 128L
  final val size = 4096

  private def run(rt: IORuntime, task: IO[Unit], size: Int): Unit = {
    IO.asyncForIO.replicateA(size, task).unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  private def isEnq(r: RandomState): IO[Boolean] =
    IO { (r.nextInt() % 2) == 0 }

  @Benchmark
  def michaelScottQueue(s: MsSt, t: KCASImplState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.michaelScottQueue.enqueue[IO](t.nextString())(t.reactive)
      else s.michaelScottQueue.tryDeque.run[IO](t.reactive)
    }
    run(s.runtime, tsk.void, size = size)
  }

  @Benchmark
  def lockedQueue(s: LockedSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.lockedQueue.enqueue(t.nextString()) }
      else IO { s.lockedQueue.tryDequeue() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  @Benchmark
  def concurrentQueue(s: JdkSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.concurrentQueue.offer(t.nextString()) }
      else IO { s.concurrentQueue.poll() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  @Benchmark
  def stmQueue(s: StmSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.stmQueue.enqueue(t.nextString()) }
      else IO { s.stmQueue.tryDequeue() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  @Benchmark
  def stmQueueC(s: StmCSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.s.commit(s.stmQueue.enqueue(t.nextString()))
      else s.s.commit(s.stmQueue.tryDequeue)
    }
    run(s.runtime, tsk.void, size = size)
  }
}

object QueueBench {

  @State(Scope.Benchmark)
  class MsSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val michaelScottQueue = new MichaelScottQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val lockedQueue = new LockedQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class JdkSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val concurrentQueue = new java.util.concurrent.ConcurrentLinkedQueue[String](Prefill.forJava())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val stmQueue = new StmQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmCSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val s = STM.runtime[IO].unsafeRunSync()(runtime)
    val qu = StmQueueCLike[STM, IO](s)
    val stmQueue = s.commit(StmQueueC.make(s)(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
  }
}
