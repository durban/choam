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
package ext

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.ExecutionContext

import org.openjdk.jmh.annotations._

import cats.syntax.all._
import cats.effect.IO

import core.{ Rxn, Ref, Reactive }
import dev.tauri.choam.bench.util.McasImplStateBase

/** This benchmark can only run on JVM >= 21, because it tests virtual threads */
@Fork(1)
@Threads(1) // thread-pool
@BenchmarkMode(Array(Mode.Throughput))
class VirtualThreadsBench {

  import VirtualThreadsBench.{ AbstractSt, BaselineSt, VirtThreadSt }

  private[this] final val N = 1024

  private def doThings(st: AbstractSt, n: Int): IO[Unit] = {
    val rxn: Rxn[String] = st.selectRndRef.flatMap { r1 =>
      r1.update(_.##.toString) *> st.selectRndRef.flatMap { r2 =>
        r2.get
      }
    }
    val tsk = st.reactive(rxn).start
    tsk.replicateA(n).flatMap { fibers =>
      fibers.traverse_(_.joinWithNever)
    }
  }

  @Benchmark
  def baseline(st: BaselineSt): Unit = {
    doThings(st, N).evalOn(st.baselineEc).unsafeRunSync()(using st.runtime)
  }

  @Benchmark
  def virtualThreads(st: VirtThreadSt): Unit = {
    doThings(st, N).evalOn(st.virtThreadEc).unsafeRunSync()(using st.runtime)
  }
}

object VirtualThreadsBench {

  final val K = 32

  @State(Scope.Benchmark)
  abstract class AbstractSt extends McasImplStateBase {

    implicit final def reactiveImplicit: Reactive[IO] =
      this.reactive

    val runtime =
      cats.effect.unsafe.IORuntime.global

    private val refs = Array.fill(K) {
      Ref.unsafePadded(ThreadLocalRandom.current().nextInt().toString, reactive.mcasImpl.currentContext().refIdGen)
    }

    def selectRndRef: Rxn[Ref[String]] = {
      Rxn.fastRandom.nextIntBounded(K).map(k => this.refs(k))
    }
  }

  @State(Scope.Benchmark)
  class BaselineSt extends AbstractSt {
    val baselineEc: ExecutionContext =
      this.runtime.compute
  }

  @State(Scope.Benchmark)
  class VirtThreadSt extends AbstractSt {

    import java.util.concurrent.{ Executors, ExecutorService }
    import java.lang.invoke.{ MethodHandle, MethodHandles, MethodType }

    val virtThreadEc: ExecutionContext = {
      val l = MethodHandles.lookup()
      val mh: MethodHandle = l.findStatic(
        classOf[Executors],
        "newVirtualThreadPerTaskExecutor",
        MethodType.methodType(classOf[ExecutorService]),
      )
      val es: ExecutorService = mh.invokeExact()
      ExecutionContext.fromExecutorService(es)
    }
  }
}
