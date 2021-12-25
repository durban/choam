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

import cats.effect.IO
import io.github.timwspence.cats.stm._

import org.openjdk.jmh.annotations._

import util._
import data.TreiberStack

// TODO: run more concurrent operations

@Fork(3)
@Threads(1) // because it runs on a threadpool
@BenchmarkMode(Array(Mode.AverageTime))
class SyncStackBench extends BenchUtils {

  import SyncStackBench._

  final override val waitTime = 0L

  final val N = 512

  @Benchmark
  def baseline(s: BaselineSt): Unit = {
    run(s.runtime, IO.unit, size = N)
  }

  @Benchmark
  def rxnTreiberStack(s: TreiberSt, ct: KCASImplState): Unit = {
    val tsk = for {
      _ <- ct.reactive.run(s.treiberStack.push, "foo")
      pr <- ct.reactive.run(s.treiberStack.tryPop, null)
      _ <- if (pr eq None) {
        IO.raiseError(Errors.EmptyStack)
      } else IO.unit
    } yield ()
    run(s.runtime, tsk, size = N)
  }

  @Benchmark
  def compareAndSetTreiberStack(s: ReferenceSt): Unit = {
    val tsk = IO {
      s.referenceStack.push("foo")
      if (s.referenceStack.tryPop() eq None) throw Errors.EmptyStack
    }
    run(s.runtime, tsk, size = N)
  }

  @Benchmark
  def lockedStack(s: LockedSt): Unit = {
    val tsk = IO {
      s.lockedStack.push("foo")
      if (s.lockedStack.tryPop() eq None) throw Errors.EmptyStack
    }
    run(s.runtime, tsk, size = N)
  }

  @Benchmark
  def stmStack(s: StmSt): Unit = {
    val tsk = IO {
      s.stmStack.push("foo")
      if (s.stmStack.tryPop() eq None) throw Errors.EmptyStack
    }
    run(s.runtime, tsk, size = N)
  }

  @Benchmark
  def stmCStack(s: StmCSt): Unit = {
    val tsk = for {
      _ <- s.s.commit(s.stmCStack.push("foo"))
      pr <- s.s.commit(s.stmCStack.tryPop)
      _ <- if (pr eq None) {
        IO.raiseError(Errors.EmptyStack)
      } else IO.unit
    } yield ()
    run(s.runtime, tsk, size = N)
  }

  @Benchmark
  def stmZStack(s: StmZSt): Unit = {
    val tsk = for {
      _ <- s.stmZStack.push("foo").commit
      pr <- s.stmZStack.tryPop.commit
      _ <- if (pr eq None) {
        zio.ZIO.fail(Errors.EmptyStack)
      } else zio.ZIO.unit
    } yield ()
    runZ(s.runtime, tsk, size = N)
  }
}

object SyncStackBench {

  @State(Scope.Benchmark)
  class BaselineSt {
    val runtime = cats.effect.unsafe.IORuntime.global
  }

  @State(Scope.Benchmark)
  class TreiberSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val treiberStack = new TreiberStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class ReferenceSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val referenceStack = new ReferenceTreiberStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class LockedSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val lockedStack = new LockedStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmSt {
    val runtime = cats.effect.unsafe.IORuntime.global
    val stmStack = new StmStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmCSt {

    val runtime = cats.effect.unsafe.IORuntime.global
    val s: STM[IO] = STM.runtime[IO](128L).unsafeRunSync()(runtime)
    val st = StmStackCLike[STM, IO](s) // scalafix:ok

    val stmCStack: st.StmStackC[String] =
      s.commit(StmStackC.make(st)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
  }

  @State(Scope.Benchmark)
  class StmZSt {

    val runtime = zio.Runtime.default

    val stmZStack: StmStackZ[String] =
      runtime.unsafeRunTask(StmStackZ.apply(Prefill.prefill().toList))
  }
}
