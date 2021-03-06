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

import cats.effect.IO

import io.github.timwspence.cats.stm.STM

import zio.stm.ZSTM

import util._

@Fork(2)
class QueueBench extends BenchUtils {

  import QueueBench._

  final val waitTime = 128L
  final val size = 4096

  /** MS-Queue implemented with `React` */
  @Benchmark
  def michaelScottQueue(s: MsSt, t: KCASImplState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.michaelScottQueue.enqueue[IO](t.nextString())(t.reactive)
      else s.michaelScottQueue.tryDeque.run[IO](t.reactive)
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queue (+ interior deletion) implemented with `React` */
  @Benchmark
  def michaelScottQueueWithRemove(s: RmSt, t: KCASImplState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.removeQueue.enqueue[IO](t.nextString())(t.reactive)
      else s.removeQueue.tryDeque.run[IO](t.reactive)
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** Simple queue protected with a reentrant lock */
  @Benchmark
  def lockedQueue(s: LockedSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.lockedQueue.enqueue(t.nextString()) }
      else IO { s.lockedQueue.tryDequeue() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** juc.ConcurrentLinkedQueue (MS-Queue in the JDK) */
  @Benchmark
  def concurrentQueue(s: JdkSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.concurrentQueue.offer(t.nextString()) }
      else IO { s.concurrentQueue.poll() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queue implemented with scala-stm */
  @Benchmark
  def stmQueue(s: StmSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) IO { s.stmQueue.enqueue(t.nextString()) }
      else IO { s.stmQueue.tryDequeue() }
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queue implemented with cats-stm */
  @Benchmark
  def stmQueueC(s: StmCSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.s.commit(s.stmQueue.enqueue(t.nextString()))
      else s.s.commit(s.stmQueue.tryDequeue)
    }
    run(s.runtime, tsk.void, size = size)
  }

  /** MS-Queue implemented with zio STM */
  @Benchmark
  def stmQueueZ(s: StmZSt, t: RandomState): Unit = {
    val tsk = isEnqZ(t).flatMap { enq =>
      if (enq) ZSTM.atomically(s.stmQueue.enqueue(t.nextString()))
      else ZSTM.atomically(s.stmQueue.tryDequeue)
    }
    runZ(s.runtime, tsk.unit, size = size)
  }

  /** MS-Queue implemented with cats-effect `Ref` */
  @Benchmark
  def ceQueue(s: CeSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.ceQueue.enqueue(t.nextString())
      else s.ceQueue.tryDequeue
    }
    run(s.runtime, tsk.void, size = size)
  }
}

object QueueBench {

  @State(Scope.Benchmark)
  class MsSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val michaelScottQueue = MichaelScottQueue.fromList(Prefill.prefill().toList).run[IO].unsafeRunSync()(runtime)
  }

  @State(Scope.Benchmark)
  class RmSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val removeQueue = RemoveQueue.fromList(Prefill.prefill().toList).run[IO].unsafeRunSync()(runtime)
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

  @State(Scope.Benchmark)
  class StmZSt {
    val runtime = zio.Runtime.default
    val stmQueue = runtime.unsafeRunTask(StmQueueZ[String](Prefill.prefill().toList))
  }

  @State(Scope.Benchmark)
  class CeSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val ceQueue = CeQueue.fromList[IO, String](Prefill.prefill().toList).unsafeRunSync()(runtime)
  }
}
