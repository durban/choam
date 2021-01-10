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
import cats.syntax.all._

import io.github.timwspence.cats.stm.STM

import zio.Task
import zio.{ Runtime => ZRuntime }

import util._

@Fork(2)
class QueueTransferBench {

  import QueueTransferBench._

  final val waitTime = 128L
  final val size = 4096

  private def isEnq(r: RandomState): IO[Boolean] =
    IO { (r.nextInt() % 2) == 0 }

  private def isEnqZ(r: RandomState): Task[Boolean] =
    Task.effect { (r.nextInt() % 2) == 0 }

  private def run(rt: IORuntime, task: IO[Unit], size: Int): Unit = {
    IO.asyncForIO.replicateA(size, task).unsafeRunSync()(rt)
    Blackhole.consumeCPU(waitTime)
  }

  private def runZ(rt: ZRuntime[_], task: Task[Unit], size: Int): Unit = {
    rt.unsafeRunTask(task.repeatN(size))
    Blackhole.consumeCPU(waitTime)
  }

  /** MS-Queues implemented with `React` */
  @Benchmark
  def michaelScottQueue(s: MsSt, ct: KCASImplState): Unit = {
    val tsk = isEnq(ct).flatMap { enq =>
      if (enq) s.enq[IO](ct.nextString())(ct.reactive)
      else s.deq.run[IO](ct.reactive)
    }

    run(s.runtime, tsk, size = size)
  }

  /** Simple queues protected with reentrant locks */
  @Benchmark
  def lockedQueue(s: LockedSt, ct: RandomState): Unit = {
    val tsk = isEnq(ct).flatMap { enq =>
      if (enq) IO { s.enq(ct.nextString()) }
      else IO { s.deq() }
    }

    run(s.runtime, tsk, size = size)
  }

  /** MS-Queues implemented with scala-stm */
  @Benchmark
  def stmQueue(s: StmSt, ct: RandomState): Unit = {
    val tsk = isEnq(ct).flatMap { enq =>
      if (enq) IO { s.enq(ct.nextString()) }
      else IO { s.deq() }
    }

    run(s.runtime, tsk, size = size)
  }

  /** MS-Queues implemented with cats-stm */
  @Benchmark
  def stmQueueC(s: StmCSt, t: RandomState): Unit = {
    val tsk = isEnq(t).flatMap { enq =>
      if (enq) s.enq(t.nextString())
      else s.deq
    }

    run(s.runtime, tsk, size = size)
  }

  /** MS-Queues implemented with zio STM */
  @Benchmark
  def stmQueueZ(s: StmZSt, t: RandomState): Unit = {
    val tsk = isEnqZ(t).flatMap { enq =>
      if (enq) s.enq(t.nextString())
      else s.deq
    }

    runZ(s.runtime, tsk, size = size)
  }
}

object QueueTransferBench {

  @State(Scope.Benchmark)
  abstract class BaseSt {

    @Param(Array("2", "4", "6"))
    private[this] var _txSize: Int = _

    def txSize: Int =
      this._txSize
  }

  @State(Scope.Benchmark)
  class MsSt extends BaseSt {

    val runtime = cats.effect.unsafe.IORuntime.global

    var deq: React[Unit, Unit] = _
    var enq: React[String, Unit] = _

    val queue0 = new MichaelScottQueue[String](Prefill.prefill())
    var queues: List[MichaelScottQueue[String]] = _

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) { new MichaelScottQueue[String](Prefill.prefill()) }
      this.deq = this.queue0.tryDeque.flatMap {
        case Some(s) => this.queues.map(_.enqueue).reduce { (x, y) =>
          (x * y).discard
        }.lmap(_ => s)
        case None => React.unit
      }
      this.enq = this.queue0.enqueue >>> this.queues.map(_.tryDeque.discard).reduce {(x, y) =>
        (x * y).discard
      }.lmap(_ => ())

      java.lang.invoke.VarHandle.releaseFence()
    }
  }

  @State(Scope.Benchmark)
  class LockedSt extends BaseSt {

    val runtime = cats.effect.unsafe.IORuntime.global

    val queue0 = new LockedQueue[String](Prefill.prefill())
    var queues: List[LockedQueue[String]] = _

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) { new LockedQueue[String](Prefill.prefill()) }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def deq(): Unit = {
      this.queue0.lock.lock()
      this.queues.foreach(_.lock.lock())
      try {
        this.queue0.unlockedTryDequeue() match {
          case Some(s) =>
            this.queues.foreach(_.unlockedEnqueue(s))
          case None =>
            ()
        }
      } finally {
        this.queue0.lock.unlock()
        this.queues.foreach(_.lock.unlock())
      }
    }

    def enq(s: String): Unit = {
      this.queue0.lock.lock()
      this.queues.foreach(_.lock.lock())
      try {
        this.queue0.enqueue(s)
        this.queues.foreach(_.tryDequeue())
      } finally {
        this.queue0.lock.unlock()
        this.queues.foreach(_.lock.unlock())
      }
    }
  }

  @State(Scope.Benchmark)
  class StmSt extends BaseSt {

    import scala.concurrent.stm._

    val runtime = cats.effect.unsafe.IORuntime.global

    val queue0 = new StmQueue[String](Prefill.prefill())
    var queues: List[StmQueue[String]] = _

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) { new StmQueue[String](Prefill.prefill()) }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def deq(): Unit = {
      atomic { implicit txn =>
        this.queue0.tryDequeue() match {
          case Some(s) =>
            this.queues.foreach(_.enqueue(s))
          case None =>
            ()
        }
      }
    }

    def enq(s: String): Unit = {
      atomic { implicit txn =>
        this.queue0.enqueue(s)
        this.queues.foreach(_.tryDequeue())
      }
    }
  }

  @State(Scope.Benchmark)
  class StmCSt extends BaseSt {

    val runtime = cats.effect.unsafe.IORuntime.global
    val s = STM.runtime[IO].unsafeRunSync()(runtime)
    val qu = StmQueueCLike[STM, IO](s)

    val queue0 = s.commit(StmQueueC.make(s)(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
    var queues: List[qu.StmQueueC[String]] = _

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) { s.commit(StmQueueC.make(s)(qu)(Prefill.prefill().toList)).unsafeRunSync()(runtime) }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def deq: IO[Unit] = {
      qu.stm.commit {
        this.queue0.tryDequeue.flatMap {
          case Some(s) =>
            this.queues.traverse(_.enqueue(s)).void
          case None =>
            qu.stm.Txn.monadForTxn.unit
        }
      }
    }

    def enq(s: String): IO[Unit] = {
      qu.stm.commit {
        this.queue0.enqueue(s) >> this.queues.traverse(_.tryDequeue).void
      }
    }
  }

  @State(Scope.Benchmark)
  class StmZSt extends BaseSt {

    import zio.Task
    import zio.stm.ZSTM

    val runtime = zio.Runtime.default

    val queue0 = runtime.unsafeRunTask(StmQueueZ[String](Prefill.prefill().toList))
    var queues: List[StmQueueZ[String]] = _

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) { runtime.unsafeRunTask(StmQueueZ[String](Prefill.prefill().toList)) }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def deq: Task[Unit] = {
      ZSTM.atomically {
        this.queue0.tryDequeue.flatMap {
          case Some(s) =>
            ZSTM.foreach_(this.queues)(_.enqueue(s))
          case None =>
            ZSTM.unit
        }
      }
    }

    def enq(s: String): Task[Unit] = {
      ZSTM.atomically {
        this.queue0.enqueue(s) *> ZSTM.foreach_(this.queues)(_.tryDequeue)
      }
    }
  }
}
