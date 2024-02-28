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
package ext

import java.util.concurrent.atomic.{ AtomicLong, AtomicReference }
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.openjdk.jmh.annotations._

import cats.effect.IO
import cats.effect.unsafe.{ IORuntime, IORuntimeBuilder }
import cats.effect.std.Dispatcher

import BackoffBenchIo._

@Fork(1)
@BenchmarkMode(Array(Mode.AverageTime))
class BackoffBenchIo extends BackoffBenchBase {

  /** How many nanoseconds to sleep when sleeping */
  private[this] final val sleepNanos =
    1000L

  /**
   * For the measurements which need IO
   * (cede and sleep) we need to `replicateA_`
   * the task we'd like to actually measure,
   * because otherwise we'd mainly just measure
   * the overhead of `unsafeRun*`.
   */
  private[this] final val replicate =
    128

  /**
   * 1B. Baseline with IO: "do something simple with shared memory."
   */
  @Benchmark
  def baselineIo(s: IoSt, t: IoThStBase): Unit = {
    val ctr = s.ctr
    // we have the .void here to be more similar to the cede/sleep measurement
    val tsk = IO { ctr.incrementAndGet() }.void
    val replicated = tsk.replicateA_(replicate)
    val allTasks = alsoStartBgIfNeeded(replicated, s, t)
    unsafeRunSync(allTasks, s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  /**
   * 3. Ceding: "do something simple with shared memory,
   * then cede to other fibers."
   */
  @Benchmark
  def cede(s: IoSt, t: IoThSt): Unit = {
    val ctr = s.ctr
    val cedeTsk = IO.cede.replicateA_(t.repeat)
    val tsk = IO { ctr.incrementAndGet() }.flatMap { _ =>
      cedeTsk
    }
    val allTasks = alsoStartBgIfNeeded(tsk.replicateA_(replicate), s, t)
    unsafeRunSync(allTasks, s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  /**
   * 4. Sleeping: "do something simple with shared memory,
   * then sleep a little."
   */
  @Benchmark
  def sleep(s: IoSt, t: IoThSt): Unit = {
    val ctr = s.ctr
    val repeatSleep = sleepNanos * t.repeat.toLong
    val tsk = IO { ctr.incrementAndGet() }.flatMap { _ =>
      IO.sleep(repeatSleep.nanos)
    }
    val allTasks = alsoStartBgIfNeeded(tsk.replicateA_(replicate), s, t)
    unsafeRunSync(allTasks, s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  // Helper methods:

  private[this] final def unsafeRunSync[A](
    tsk: IO[A],
    runtime: IORuntime,
    parkJmhThread: Boolean,
    dispatcher: Dispatcher[IO],
    pec: ExecutionContext,
  ): A = {
    if (parkJmhThread) {
      dispatcher match {
        case null =>
          tsk.unsafeRunSync()(runtime)
        case d =>
          d.unsafeRunSync(tsk)
      }
    } else {
      unsafeRunSyncWithSpinLock(tsk, runtime, dispatcher = dispatcher, pec = pec)
    }
  }

  private[this] final def unsafeRunSyncWithSpinLock[A](
    tsk: IO[A],
    runtime: IORuntime,
    dispatcher: Dispatcher[IO],
    pec: ExecutionContext,
  ): A = {
    val resultHolder = new AtomicReference[Either[Throwable, A]]
    if (dispatcher eq null) {
      tsk.unsafeRunAsync { result =>
        resultHolder.set(result)
      } (runtime)
    } else {
      dispatcher.unsafeToFuture(tsk).onComplete { resultTry =>
        resultHolder.set(resultTry.toEither)
      } (pec)
    }
    var result = resultHolder.get()
    while (result eq null) {
      Thread.onSpinWait()
      result = resultHolder.get()
    }
    result.fold(throw _, a => a)
  }

  private[this] final def modifyAtomicLong[B](r: AtomicLong, f: Long => (Long, B)): IO[B] = {
    def go(ov: Long): B = {
      val (nv, b) = f(ov)
      val wit = r.compareAndExchange(ov, nv)
      if (wit == ov) {
        b
      } else {
        go(wit)
      }
    }
    IO { go(r.get()) }
  }

  /**
   * Background: run some fibers which do something with
   * shared memory, so none of the compute threads go to
   * sleep while we run the actual measurement. Also touch
   * the same memory that is used by the main measurement.
   */
  private[this] final def startBackgroundTasksIfNeeded(s: IoSt, t: IoThStBase): IO[Unit] = {
    val busyCounter = s.busyCounter
    val wstpSize = s.wstpSize
    val globalCtr = s.ctr
    val globalRnd = s.rnd
    val threadLocalCtr = t.threadLocalCtr
    val threadLocalRnd = t.threadLocalRnd
    val contendedBg = t.contendedBg

    modifyAtomicLong(busyCounter, { ov =>
      if (ov == 0L) {
        // not started yet, we need to start it:
        val simple = if (contendedBg) {
          IO { simpleThing(globalCtr, globalRnd) }
        } else {
          IO { simpleThing(threadLocalCtr, threadLocalRnd) }
        }
        val busyTaskOnce = IO { ThreadLocalRandom.current().nextInt(4, 33) }.flatMap { randomSize =>
          simple
            .replicateA_(randomSize)
            .guarantee(IO { busyCounter.getAndIncrement(); () })
            .guarantee(IO.cede)
        }
        val busyTaskForever = IO.asyncForIO.tailRecM(1L) { busyCtr =>
          if (busyCtr > 0L) {
            busyTaskOnce *> IO { Left(busyCounter.get()) }
          } else {
            // overflow (unlikely), or we received stop signal:
            IO.pure(Right(()))
          }
        }
        (1L, busyTaskForever.start.replicateA_(wstpSize))
      } else {
        // it's already running, we do nothing:
        (ov, IO.unit)
      }
    }).flatten
  }

  /**
   * Tries to be as fast as possible in checking
   * if background tasks are running. (The common
   * case is that they are running.) If not, returns
   * the `IO` which will start them.
   */
  private[this] final def startBgIfNeeded(s: IoSt, t: IoThStBase): IO[Unit] = {
    if (t.bgIsRunning) {
      // OK, it's already running
      null
    } else if (s.busyCounter.getOpaque() != 0L) {
      // it's running, we just didn't previously seen it
      t.bgIsRunning = true // cache the info in thread-local
      null
    } else if (s.busyCounter.get() != 0L) {
      // it's running, we just needed a memory fence to see it
      t.bgIsRunning = true
      null
    } else {
      t.bgIsRunning = true // technically not yet, but will soon
      startBackgroundTasksIfNeeded(s, t)
    }
  }

  private[this] final def alsoStartBgIfNeeded(tsk: IO[Unit], s: IoSt, t: IoThStBase): IO[Unit] = {
    startBgIfNeeded(s, t) match {
      case null => tsk
      case startTask => startTask *> tsk
    }
  }
}

object BackoffBenchIo {

  private[this] val ceRuntime =
    new AtomicReference[IORuntime]

  final val wstpSize: Int = {
    val numCpu = Runtime.getRuntime().availableProcessors()
    assert(numCpu >= 4)
    2 // 2 threads for WSTP + 2 threads for JMH
  }

  private[this] final def getRuntime(): IORuntime = {
    ceRuntime.get() match {
      case null =>
        val (wstp, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = wstpSize)
        val rt = IORuntimeBuilder().setCompute(wstp, shutdown).build()
        val wit = ceRuntime.compareAndExchange(null, rt)
        if (wit eq null) {
          // we cheat, and never shut down this
          // runtime, but JMH will shut down the
          // whole JVM anyway
          rt
        } else {
          rt.shutdown()
          wit
        }
      case rt =>
        rt
    }
  }

  @State(Scope.Benchmark)
  class IoSt extends BackoffBenchSync.St {

    /**
     * Whether to wait for the `IO`
     * with a lock which parks the
     * JMH thread, or with a custom
     * spinlock.
     */
    @Param(Array("false")) // spinlock is faster
    var parkJmhThread =
      false

    final val runtime: IORuntime =
      getRuntime()

    final val wstpSize: Int =
      BackoffBenchIo.wstpSize

    final val busyCounter: AtomicLong =
      new AtomicLong
  }

  @State(Scope.Thread)
  class IoThStBase extends BackoffBenchSync.ThStBase {

    /**
     * Whether to use a `Dispatcher`
     * to execute the `IO` (or just
     * use `usafeRun*`).
     */
    @Param(Array("true")) // Dispatcher is faster
    var useDispatcher =
      false

    var dispatcher: Dispatcher[IO] =
      null

    private[this] var closeDispatcher: IO[Unit] =
      null

    var bgIsRunning: Boolean =
      false

    /** "Parasitic" EC */
    final val pec: ExecutionContext = new ExecutionContext {

      final override def execute(runnable: Runnable): Unit =
        runnable.run()

      final override def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace()
    }

    @Setup
    def setup(): Unit = {
      if (this.useDispatcher) {
        val (dp, close) = Dispatcher.sequential[IO].allocated.unsafeRunSync()(getRuntime())
        this.closeDispatcher = close
        this.dispatcher = dp
      }
    }

    @TearDown
    def shutdown(): Unit = {
      closeDispatcher match {
        case null =>
          ()
        case close =>
          close.unsafeRunAndForget()(getRuntime())
      }
    }
  }

  @State(Scope.Thread)
  class IoThSt extends IoThStBase {

    /**
     * We repeat the backoff actions a number
     * of times, to make sure our measurement
     * makes sense. (E.g., the measured time
     * for `2` should be approximately twice
     * as long as for `1`.)
     */
    @Param(Array("1", "2", "4"))
    var repeat: Int =
      0
  }
}
