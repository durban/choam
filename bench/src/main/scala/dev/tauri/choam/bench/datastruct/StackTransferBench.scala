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

import cats.effect.SyncIO

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util._
import core.Rxn
import data.Stack

@Fork(3)
class StackTransferBench {

  import StackTransferBench._

  final val waitTime = 128L

  @Benchmark
  def treiberStack(s: TreiberSt, bh: Blackhole, ct: McasImplState, rnd: RandomState): Unit = {
    bh.consume(s.treiberStack1.push(rnd.nextString()).unsafePerform(ct.mcasImpl))
    bh.consume(s.transfer.unsafePerform(ct.mcasImpl))
    if (s.treiberStack2.tryPop.unsafePerform(ct.mcasImpl) eq None) throw Errors.EmptyStack
    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def lockedStack(s: LockedSt, bh: Blackhole, ct: RandomState): Unit = {
    bh.consume(s.lockedStack1.push(ct.nextString()))

    s.lockedStack1.lock.lock()
    s.lockedStack2.lock.lock()
    try {
      val item = s.lockedStack1.unlockedTryPop().get
      bh.consume(s.lockedStack2.unlockedPush(item))
    } finally {
      s.lockedStack1.lock.unlock()
      s.lockedStack2.lock.unlock()
    }

    if (s.lockedStack2.tryPop() eq None) throw Errors.EmptyStack

    Blackhole.consumeCPU(waitTime)
  }

  @Benchmark
  def stmStack(s: StmSt, bh: Blackhole, ct: RandomState): Unit = {
    import scala.concurrent.stm._
    bh.consume(s.stmStack1.push(ct.nextString()))
    bh.consume(atomic { implicit txn =>
      val item = s.stmStack1.tryPop().get
      s.stmStack2.push(item)
    })
    if (s.stmStack2.tryPop() eq None) throw Errors.EmptyStack
    Blackhole.consumeCPU(waitTime)
  }
}

object StackTransferBench {

  @State(Scope.Benchmark)
  class TreiberSt {
    val treiberStack1: Stack[String] =
      Stack.fromList[SyncIO, String](Stack.apply)(Prefill.prefill())(using McasImplStateBase.reactiveSyncIO).unsafeRunSync()
    val treiberStack2: Stack[String] =
      Stack.fromList[SyncIO, String](Stack.apply)(Prefill.prefill())(using McasImplStateBase.reactiveSyncIO).unsafeRunSync()
    val transfer: Rxn[Unit] =
      treiberStack1.tryPop.map(_.get).flatMap(treiberStack2.push)
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val lockedStack1 =
      new LockedStack[String](Prefill.prefill())
    val lockedStack2 =
      new LockedStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val stmStack1 =
      new StmStack[String](Prefill.prefill())
    val stmStack2 =
      new StmStack[String](Prefill.prefill())
  }
}
