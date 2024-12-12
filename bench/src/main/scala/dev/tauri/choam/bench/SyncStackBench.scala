/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.{ IO, SyncIO }
import io.github.timwspence.cats.stm._

import org.openjdk.jmh.annotations._

import util._
import data.Stack
import helpers.StackHelper
import ce._

@Fork(2)
@Threads(1) // set it to _concurrentOps!
@BenchmarkMode(Array(Mode.AverageTime))
class SyncStackBench extends BenchUtils {

  import SyncStackBench._

  final val N = 480 // divisible by _concurrentOps

  @Benchmark
  def baseline(s: BaselineSt): Unit = {
    runRepl(s.runtime, IO.unit, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def rxnTreiberStack(s: TreiberSt, ct: McasImplState): Unit = {
    val tsk = for {
      _ <- ct.reactive.apply(s.treiberStack.push, "foo")
      pr <- ct.reactive.run(s.treiberStack.tryPop)
      _ <- if (pr eq None) {
        IO.raiseError(Errors.EmptyStack)
      } else IO.unit
    } yield ()
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def rxnEliminationStack(s: EliminationSt, ct: McasImplState): Unit = {
    val tsk = for {
      _ <- ct.reactive.apply(s.eliminationStack.push, "foo")
      pr <- ct.reactive.run(s.eliminationStack.tryPop)
      _ <- if (pr eq None) {
        IO.raiseError(Errors.EmptyStack)
      } else IO.unit
    } yield ()
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def compareAndSetTreiberStack(s: ReferenceSt): Unit = {
    val tsk = IO {
      s.referenceStack.push("foo")
      if (s.referenceStack.tryPop() eq None) throw Errors.EmptyStack
    }
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def lockedStack(s: LockedSt): Unit = {
    val tsk = IO {
      s.lockedStack.push("foo")
      if (s.lockedStack.tryPop() eq None) throw Errors.EmptyStack
    }
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
  }

  @Benchmark
  def stmStack(s: StmSt): Unit = {
    val tsk = IO {
      s.stmStack.push("foo")
      if (s.stmStack.tryPop() eq None) throw Errors.EmptyStack
    }
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
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
    runRepl(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
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
    runReplZ(s.runtime, tsk, size = N, parallelism = s.concurrentOps)
  }
}

object SyncStackBench {

  @State(Scope.Benchmark)
  abstract class BaseSt {

    @Param(Array("2", "4", "6", "8", "10"))
    @nowarn("cat=unused-privates")
    private[this] var _concurrentOps: Int = _

    def concurrentOps: Int =
      this._concurrentOps
  }

  @State(Scope.Benchmark)
  class BaselineSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
  }

  @State(Scope.Benchmark)
  class TreiberSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val treiberStack: Stack[String] =
      StackHelper.treiberStackFromList[SyncIO, String](Prefill.prefill()).unsafeRunSync()
  }

  @State(Scope.Benchmark)
  class EliminationSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val eliminationStack: Stack[String] =
      StackHelper.eliminationStackFromList[SyncIO, String](Prefill.prefill()).unsafeRunSync()
  }

  @State(Scope.Benchmark)
  class ReferenceSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val referenceStack =
      new ReferenceTreiberStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class LockedSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val lockedStack =
      new LockedStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val stmStack =
      new StmStack[String](Prefill.prefill())
  }

  @State(Scope.Benchmark)
  class StmCSt extends BaseSt {
    val runtime =
      cats.effect.unsafe.IORuntime.global
    val s: STM[IO] =
      STM.runtime[IO](128L).unsafeRunSync()(runtime)
    val st =
      StmStackCLike[STM, IO](s) // scalafix:ok
    val stmCStack: st.StmStackC[String] =
      s.commit(StmStackC.make(st)(Prefill.prefill().toList)).unsafeRunSync()(runtime)
  }

  @State(Scope.Benchmark)
  class StmZSt extends BaseSt {

    val runtime =
      zio.Runtime.default

    val stmZStack: StmStackZ[String] = {
      zio.Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(StmStackZ.apply(Prefill.prefill().toList)).getOrThrow()
      }
    }
  }
}
