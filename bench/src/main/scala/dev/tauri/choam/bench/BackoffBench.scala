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

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference }
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import cats.effect.IO
import cats.effect.unsafe.{ IORuntime, IORuntimeBuilder }
import cats.effect.std.Dispatcher

import BackoffBench._

@Fork(1)
@BenchmarkMode(Array(Mode.AverageTime))
class BackoffBench {

  /** How many `onSpinWait` calls to do when spinning */
  private[this] final val spinTokens =
    7

  /** How many nanoseconds to sleep when sleeping */
  private[this] final val sleepNanos =
    1000L

  private[this] final val gamma =
    0x9e3779b97f4a7c15L

  /**
   * For the measurements which need IO
   * (cede and sleep) we need to `replicateA_`
   * the task we'd like to actually measure,
   * because otherwise we'd mainly just measure
   * the overhead of `unsafeRun*`.
   */
  private[this] final val replicate =
    128

  // 1. Baseline: no backoff at all (with and without IO)

  /**
   * 1A. Baseline without IO (main)
   *
   * Main measurement: "do something simple with shared memory."
   */
  @Benchmark
  @Group("baseline")
  def baselineMain(s: St): Int = {
    s.ctr.getAndIncrement()
  }

  /**
   * 1A. Baseline without IO (bg)
   *
   * Background: "do something more complicated" (just so that
   * the "simple thing" is not the only operation). Also touch
   * the same memory that is used by the main measurement.
   */
  @Benchmark
  @Group("baseline")
  def baselineBackground(s: St, t: ThStBase): Unit = {
    background(s, t)
  }

  /**
   * 1B. Baseline with IO (main)
   *
   * Main measurement: "do something simple with shared memory."
   */
  @Benchmark
  @Group("baselineIo")
  def baselineIoMain(s: IoSt, t: IoThSt): Unit = {
    val ctr = s.ctr
    // we have the .void here to be more similar to the cede/sleep measurement
    val tsk = IO { ctr.incrementAndGet() }.void
    unsafeRunSync(
      tsk.replicateA_(replicate),
      s.runtime,
      s.parkJmhThread,
      t.dispatcher,
      t.pec,
    )
  }

  /**
   * 1B. Baseline with IO (bg)
   *
   * Background: run some fibers which do something with
   * shared memory, so none of the compute threads go to
   * sleep while we run the actual measurement. Also touch
   * the same memory that is used by the main measurement.
   */
  @Benchmark
  @Group("baselineIo")
  def baselineIoBackground(s: IoSt, t: IoThSt): Unit = {
    unsafeRunSync(ioBackground(s, t), s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  // 2. Spinning (without IO)

  /**
   * 2. Spinning (main)
   *
   * Main measurement: "do something simple with shared memory,
   * then spin a little."
   */
  @Benchmark
  @Group("onSpinWait")
  def onSpinWaitMain(s: St, t: ThSt, bh: Blackhole): Unit = {
    bh.consume(s.ctr.getAndIncrement())
    onSpinWait(spinTokens * t.repeat)
  }

  /**
   * 2. Spinning (bg)
   *
   * Background: "do something more complicated" (just so that
   * the "simple thing" is not the only operation). Also touch
   * the same memory that is used by the main measurement.
   * (No spinning here.)
   */
  @Benchmark
  @Group("onSpinWait")
  def onSpinWaitBackground(s: St, t: ThStBase): Unit = {
    background(s, t)
  }

  // 3. Ceding (IO)

  /**
   * 3. Ceding (main)
   *
   * Main measurement: "do something simple with shared memory,
   * then cede to other fibers."
   */
  @Benchmark
  @Group("cede")
  def cedeMain(s: IoSt, t: IoThSt): Unit = {
    val ctr = s.ctr
    val cedeTsk = IO.cede.replicateA_(t.repeat)
    val tsk = IO { ctr.incrementAndGet() }.flatMap { _ =>
      cedeTsk
    }
    unsafeRunSync(tsk.replicateA_(replicate), s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  /**
   * 3. Ceding (bg)
   *
   * Background: run some fibers which do something with
   * shared memory, so none of the compute threads go to
   * sleep while we run the actual measurement. Also touch
   * the same memory that is used by the main measurement.
   * (No ceding here.)
   */
  @Benchmark
  @Group("cede")
  def cedeBackground(s: IoSt, t: IoThSt): Unit = {
    unsafeRunSync(ioBackground(s, t), s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  // 4. Sleeping (IO)

  /**
   * 4. Sleeping (main)
   *
   * Main measurement: "do something simple with shared memory,
   * then sleep a little."
   */
  @Benchmark
  @Group("sleep")
  def sleepMain(s: IoSt, t: IoThSt): Unit = {
    val ctr = s.ctr
    val repeatSleep = sleepNanos * t.repeat.toLong
    val tsk = IO { ctr.incrementAndGet() }.flatMap { _ =>
      IO.sleep(repeatSleep.nanos)
    }
    unsafeRunSync(tsk.replicateA_(replicate), s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
  }

  /**
   * 4. Sleeping (bg)
   *
   * Background: run some fibers which do something with
   * shared memory, so none of the compute threads go to
   * sleep while we run the actual measurement. Also touch
   * the same memory that is used by the main measurement.
   * (No sleeping here.)
   */
  @Benchmark
  @Group("sleep")
  def sleepBackground(s: IoSt, t: IoThSt): Unit = {
    unsafeRunSync(ioBackground(s, t), s.runtime, s.parkJmhThread, t.dispatcher, t.pec)
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

  @tailrec
  private[this] final def onSpinWait(n: Int): Unit = {
    if (n > 0) {
      Thread.onSpinWait()
      onSpinWait(n - 1)
    }
  }

  private[this] final def background(s: St, t: ThStBase): Unit = {
    if (t.contendedBg) {
      simpleThing(s.ctr, s.rnd)
    } else {
      simpleThing(t.threadLocalCtr, t.threadLocalRnd)
    }
  }

  private[this] final def simpleThing(ctr: AtomicInteger, rnd: AtomicLong): Unit = {
    @tailrec
    def go(): Unit = {
      val ov = rnd.get()
      val nv = ov + gamma
      if (!rnd.compareAndSet(ov, nv)) {
        go()
      }
    }

    ctr.getAndIncrement()
    go()
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

  private[this] final def ioBackground(s: IoSt, t: ThStBase): IO[Unit] = {
    val busyCounter = s.busyCounter
    val wstpSize = s.wstpSize
    val globalCtr = s.ctr
    val globalRnd = s.rnd
    val threadLocalCtr = t.threadLocalCtr
    val threadLocalRnd = t.threadLocalRnd
    val contendedBg = t.contendedBg
    val startBusyTasksIfNeeded: IO[Unit] = modifyAtomicLong(busyCounter, { ov =>
      if (ov == 0L) {
        // not started yet, we need to start it:
        val simple = if (contendedBg) {
          IO { simpleThing(globalCtr, globalRnd) }
        } else {
          IO { simpleThing(threadLocalCtr, threadLocalRnd) }
        }
        val busyTaskOnce = simple
          .replicateA_(32)
          .guarantee(IO { busyCounter.getAndIncrement(); () })
          .guarantee(IO.cede)
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

    startBusyTasksIfNeeded *> IO { busyCounter.get() }.flatMap { startVal =>
      val stopVal = startVal + 0xffffL
      IO.sleep(0.1.millis).whileM_(IO { busyCounter.get() }.map { _ < stopVal })
    }
  }
}

object BackoffBench {

  private[this] val ceRuntime =
    new AtomicReference[IORuntime]

  final val wstpSize: Int = {
    // there are 2 JMH threads spinning, and the
    // rest of the CPUs should run WSTP threads:
    val numCpu = Runtime.getRuntime().availableProcessors()
    assert(numCpu >= 4)
    numCpu - 2
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
  class St {

    final val ctr =
      new AtomicInteger

    final val rnd =
      new AtomicLong(ThreadLocalRandom.current().nextLong())
  }

  @State(Scope.Benchmark)
  class IoSt extends St {

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
      BackoffBench.wstpSize

    final val busyCounter: AtomicLong =
      new AtomicLong
  }

  @State(Scope.Thread)
  class ThStBase {

    /**
     * Whether the operations running
     * in the background should use the
     * same shared memory locations as
     * the main operations.
     */
    @Param(Array("true", "false"))
    var contendedBg =
      false

    final val threadLocalCtr =
      new AtomicInteger

    final val threadLocalRnd =
      new AtomicLong(ThreadLocalRandom.current().nextLong())
  }

  @State(Scope.Thread)
  class ThSt extends ThStBase {

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

  @State(Scope.Thread)
  class IoThSt extends ThSt {

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
      _

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
}
