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

import cats.effect.{ IO, SyncIO }

import io.github.timwspence.cats.stm.STM
import zio.stm.ZSTM

import util._
import core.Rxn
import data.{ Queue, QueueHelper, RemoveQueue }
import ce.unsafeImplicits._

@Fork(1)
class QueueTransferBench extends BenchUtils {

  import QueueTransferBench._

  final override def waitTime = 128L
  final val size = 4096

  /** MS-Queues (padded) implemented with `Rxn` */
  @Benchmark
  def michaelScottQueuePadded(s: MsSt, ct: McasImplState): Unit = {
    runIdx(s.runtime, s.transfer(_).run[IO](using ct.reactive), size = size)
  }

  /** MS-Queues (unpadded) implemented with `Rxn` */
  @Benchmark
  def michaelScottQueueUnpadded(s: MsuSt, ct: McasImplState): Unit = {
    runIdx(s.runtime, s.transfer(_).run[IO](using ct.reactive), size = size)
  }

  /** MS-Queues (+ interior deletion) implemented with `Rxn` */
  @Benchmark
  def michaelScottQueueWithRemove(s: RmSt, ct: McasImplState): Unit = {
    runIdx(s.runtime, s.transfer(_).run[IO](using ct.reactive), size = size)
  }

  /** Simple queues protected with reentrant locks */
  @Benchmark
  def lockedQueue(s: LockedSt): Unit = {
    runIdx(s.runtime, idx => IO { s.transfer(idx) }, size = size)
  }

  /** MS-Queues implemented with scala-stm */
  @Benchmark
  def stmQueue(s: StmSt): Unit = {
    runIdx(s.runtime, idx => IO { s.transfer(idx) }, size = size)
  }

  /** MS-Queues implemented with cats-stm */
  @Benchmark
  def stmQueueC(s: StmCSt): Unit = {
    runIdx(s.runtime, idx => s.s.commit(s.transfer(idx)), size = size)
  }

  /** MS-Queues implemented with zio STM */
  @Benchmark
  def stmQueueZ(s: StmZSt): Unit = {
    runIdxZ(s.runtime, idx => ZSTM.atomically(s.transfer(idx)), size = size)
  }
}

object QueueTransferBench {

  @State(Scope.Benchmark)
  abstract class BaseSt {

    @Param(Array(/*"2",*/ "4"/*, "6"*/))
    @nowarn("cat=unused-privates")
    private[this] var _txSize: Int = 0

    def txSize: Int =
      this._txSize

    def circleSize: Int =
      4
  }

  abstract class MsStBase extends BaseSt {

    protected def newQueue(): Queue[String]

    val runtime = cats.effect.unsafe.IORuntime.global

    def transfer(idx: Int): Rxn[Unit] = {
      def transferOne(circle: List[Queue[String]]): Rxn[Unit] = {
        circle(idx % circleSize).poll.map(_.get).flatMap(circle((idx + 1) % circleSize).enqueue)
      }

      this.queues.map(transferOne(_)).reduce(_ *> _)
    }

    var queues: List[List[Queue[String]]] = null

    protected def internalSetup(): Unit = {
      this.queues = List.fill(this.txSize) {
        List.fill(this.circleSize) { this.newQueue() }
      }
      java.lang.invoke.VarHandle.releaseFence()
    }
  }

  @State(Scope.Benchmark)
  class MsSt extends MsStBase {

    protected override def newQueue(): Queue[String] =
      QueueHelper.msQueueFromList[SyncIO, String](Prefill.prefill().toList).unsafeRunSync()

    @Setup
    def setup(): Unit =
      internalSetup()
  }

  @State(Scope.Benchmark)
  class MsuSt extends MsStBase {

    protected override def newQueue(): Queue[String] =
      QueueHelper.msQueueUnpaddedFromList[SyncIO, String](Prefill.prefill().toList).unsafeRunSync()

    @Setup
    def setup(): Unit =
      internalSetup()
  }

  @State(Scope.Benchmark)
  class RmSt extends MsStBase {

    protected override def newQueue(): Queue[String] =
      QueueHelper.fromList[SyncIO, Queue, String](RemoveQueue[String])(Prefill.prefill().toList).unsafeRunSync()

    @Setup
    def setup(): Unit =
      internalSetup()
  }

  @State(Scope.Benchmark)
  class LockedSt extends BaseSt {

    val runtime = cats.effect.unsafe.IORuntime.global

    var queues: List[List[LockedQueue[String]]] = null

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) {
        List.fill(this.circleSize) { new LockedQueue[String](Prefill.prefill()) }
      }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def transfer(idx: Int): Unit = {
      def transferOne(
        circle: List[LockedQueue[String]],
        fromIdx: Int,
        toIdx: Int,
      ): Unit = {
        val qFrom = circle(fromIdx)
        val qTo = circle(toIdx)
        qTo.unlockedEnqueue(qFrom.unlockedTryDequeue().get)
      }

      val i1 = idx % circleSize
      val i2 = (idx + 1) % circleSize
      val (iFirst, iSecond) = if (i1 < i2) i1 -> i2 else i2 -> i1
      for (circle <- this.queues) {
        circle(iFirst).lock.lock()
        circle(iSecond).lock.lock()
      }
      for (circle <- this.queues) {
        transferOne(circle, fromIdx = i1, toIdx = i2)
      }
      for (circle <- this.queues) {
        circle(iSecond).lock.unlock()
        circle(iFirst).lock.unlock()
      }
    }
  }

  @State(Scope.Benchmark)
  class StmSt extends BaseSt {

    import scala.concurrent.stm._

    val runtime = cats.effect.unsafe.IORuntime.global

    var queues: List[List[StmQueue[String]]] = null

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) {
        List.fill(this.circleSize) { new StmQueue[String](Prefill.prefill()) }
      }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def transfer(idx: Int): Unit = {
      atomic { implicit txn =>
        def transferOne(circle: List[StmQueue[String]])(implicit txn: InTxn): Unit = {
          val qFrom = circle(idx % circleSize)
          val qTo = circle((idx + 1) % circleSize)
          qTo.enqueue(qFrom.tryDequeue().get)
        }

        for (circle <- this.queues) {
          transferOne(circle)(using txn)
        }
      }
    }
  }

  @State(Scope.Benchmark)
  class StmCSt extends BaseSt {

    val runtime = cats.effect.unsafe.IORuntime.global
    val s: STM[IO] = STM.runtime[IO].unsafeRunSync()(using runtime)
    val qu = StmQueueCLike[STM, IO](s) // scalafix:ok

    var queues: List[List[qu.StmQueueC[String]]] = null

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) {
        List.fill(this.circleSize) {
          s.commit(StmQueueC.make(qu)(Prefill.prefill().toList)).unsafeRunSync()(using runtime)
        }
      }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def transfer(idx: Int): s.Txn[Unit] = {
      def transferOne(circle: List[qu.StmQueueC[String]]): s.Txn[Unit] = {
        val qFrom = circle(idx % circleSize)
        val qTo = circle((idx + 1) % circleSize)
        qFrom.tryDequeue.map(_.get).flatMap { s => qTo.enqueue(s) }
      }

      this.queues.map(transferOne(_)).reduce(_ *> _)
    }
  }

  @State(Scope.Benchmark)
  class StmZSt extends BaseSt {

    val runtime = zio.Runtime.default

    var queues: List[List[StmQueueZ[String]]] = null

    @Setup
    def setup(): Unit = {
      this.queues = List.fill(this.txSize) {
        List.fill(this.circleSize) {
          zio.Unsafe.unsafe { implicit u =>
            runtime.unsafe.run(StmQueueZ[String](Prefill.prefill().toList)).getOrThrow()
          }
        }
      }
      java.lang.invoke.VarHandle.releaseFence()
    }

    def transfer(idx: Int): ZSTM[Any, Throwable, Unit] = {
      def transferOne(circle: List[StmQueueZ[String]]): ZSTM[Any, Throwable, Unit] = {
        val qFrom = circle(idx % circleSize)
        val qTo = circle((idx + 1) % circleSize)
        qFrom.tryDequeue.map(_.get).flatMap { s => qTo.enqueue(s) }
      }

      this.queues.map(transferOne(_)).reduce(_ *> _)
    }
  }
}
