/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.{ IO, SyncIO }

import io.github.timwspence.cats.stm.STM

import zio.stm.ZSTM

import util._
import data.{ Queue, QueueHelper }
import ce._

@Fork(2)
@Threads(1) // set it to _concurrentOps!
class SyncUnboundedQueueBench extends BenchUtils {

  import SyncUnboundedQueueBench._

  final val N = 3840 // divisible by _concurrentOps

  /** MS-Queue implemented with `Rxn` */
  @Benchmark
  def msQueue(s: MsSt, t: McasImplState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) s.michaelScottQueue.enqueue[IO](t.nextString())(t.reactive)
      else s.michaelScottQueue.tryDeque.run[IO](t.reactive)
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** MS-Queue (+ interior deletion) implemented with `Rxn` */
  @Benchmark
  def msQueueWithRemove(s: RmSt, t: McasImplState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) s.removeQueue.enqueue[IO](t.nextString())(t.reactive)
      else s.removeQueue.tryDeque.run[IO](t.reactive)
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** Simple queue protected with a reentrant lock */
  @Benchmark
  def lockedQueue(s: LockedSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) IO { s.lockedQueue.enqueue(t.nextString()) }
      else IO { s.lockedQueue.tryDequeue() }
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** juc.ConcurrentLinkedQueue (MS-Queue in the JDK) */
  @Benchmark
  def jdkQueue(s: JdkSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) IO { s.concurrentQueue.offer(t.nextString()) }
      else IO { s.concurrentQueue.poll() }
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** MS-Queue implemented with scala-stm */
  @Benchmark
  def stmQueueS(s: StmSSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) IO { s.stmQueue.enqueue(t.nextString()) }
      else IO { s.stmQueue.tryDequeue() }
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** MS-Queue implemented with cats-stm */
  @Benchmark
  def stmQueueC(s: StmCSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) s.s.commit(s.stmQueue.enqueue(t.nextString()))
      else s.s.commit(s.stmQueue.tryDequeue)
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  /** MS-Queue implemented with ZSTM */
  @Benchmark
  def stmQueueZ(s: StmZSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanZIO.flatMap { enq =>
      if (enq) ZSTM.atomically(s.stmQueue.enqueue(t.nextString()))
      else ZSTM.atomically(s.stmQueue.tryDequeue)
    }
    runReplZ(s.runtime, tsk.unit, size = N, parallelism = s.concurrentOps)
  }

  /** MS-Queue implemented with cats-effect `Ref` */
  @Benchmark
  def ceRefMsQueue(s: CeRefSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) s.ceQueue.enqueue(t.nextString())
      else s.ceQueue.tryDequeue
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def jcToolsQueue(s: JctSt, t: RandomState): Unit = {
    val tsk = t.nextBooleanIO.flatMap { enq =>
      if (enq) IO { s.jctQueue.offer(t.nextString()) }
      else IO { Option(s.jctQueue.poll()) }
    }
    runRepl(s.runtime, tsk.void, size = N, parallelism = s.concurrentOps)
  }
}

object SyncUnboundedQueueBench {

  @State(Scope.Benchmark)
  abstract class BaseSt {

    @Param(Array("2", "4", "6", "8", "10"))
    @nowarn("cat=unused-privates")
    private[this] var _concurrentOps: Int = _

    def concurrentOps: Int =
      this._concurrentOps
  }

  @State(Scope.Benchmark)
  class MsSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val michaelScottQueue: Queue[String] =
      QueueHelper.msQueueFromList[SyncIO, String](Prefill.prefill().toList).unsafeRunSync()
  }

  @State(Scope.Benchmark)
  class RmSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val removeQueue: Queue.WithRemove[String] =
      QueueHelper.fromList[SyncIO, Queue.WithRemove, String](Queue.unboundedWithRemove[String])(Prefill.prefill().toList).unsafeRunSync()
  }

  @State(Scope.Benchmark)
  class LockedSt extends BaseSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val lockedQueue = new LockedQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class JdkSt extends BaseSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val concurrentQueue = new java.util.concurrent.ConcurrentLinkedQueue[String](Prefill.forJava())
  }

  @State(Scope.Benchmark)
  class StmSSt extends BaseSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val stmQueue = new StmQueue[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmCSt extends BaseSt {
    // scalafix:off
    val runtime = cats.effect.unsafe.IORuntime.global
    val s = STM.runtime[IO].unsafeRunSync()(runtime)
    val qu = StmQueueCLike[STM, IO](s)
    val stmQueue = s.commit(StmQueueC.make(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
    // scalafix:on
  }

  @State(Scope.Benchmark)
  class StmZSt extends BaseSt {
    val runtime = zio.Runtime.default
    val stmQueue: StmQueueZ[String] = zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(StmQueueZ[String](Prefill.prefill().toList)).getOrThrow()
    }
  }

  @State(Scope.Benchmark)
  class CeRefSt extends BaseSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val ceQueue: CeQueue[IO, String] = CeQueue.fromList[IO, String](Prefill.prefill().toList).unsafeRunSync()(runtime)
  }

  @State(Scope.Benchmark)
  class JctSt extends BaseSt {

    import org.jctools.queues.MpmcUnboundedXaddArrayQueue

    val runtime =
      cats.effect.unsafe.IORuntime.global

    val jctQueue: MpmcUnboundedXaddArrayQueue[String] = {
      val q = new MpmcUnboundedXaddArrayQueue[String](16)
      Prefill.prefill().foreach { item =>
        q.offer(item)
      }
      q
    }
  }
}
