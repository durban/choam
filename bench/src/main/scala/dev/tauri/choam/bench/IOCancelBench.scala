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

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import scala.concurrent.duration._

import cats.effect.IO

import org.openjdk.jmh.annotations._

@Fork(3)
@Threads(1) // thread-pool
class IOCancelBench {

  final val N = 128

  @Benchmark
  def cooperative1Finish(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppable1 { stop =>
      s.cooperative(s.ref, stop)
    }
    s.runAndFinishTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperative1Cancel(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppable1 { stop =>
      s.cooperative(s.ref, stop)
    }
    s.runAndCancelTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeSimplerFinish(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppableSimpler { stop =>
      s.cooperative(s.ref, stop)
    }
    s.runAndFinishTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeSimplerCancel(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppableSimpler { stop =>
      s.cooperative(s.ref, stop)
    }
    s.runAndCancelTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeRelAcq1Finish(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppable1 { stop =>
      s.cooperativeRelAcq(s.ref, stop)
    }
    s.runAndFinishTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeRelAcq1Cancel(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppable1 { stop =>
      s.cooperativeRelAcq(s.ref, stop)
    }
    s.runAndCancelTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeRelAcqSimplerFinish(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppableSimpler { stop =>
      s.cooperativeRelAcq(s.ref, stop)
    }
    s.runAndFinishTask(tsk, replicas = N)
  }

  @Benchmark
  def cooperativeRelAcqSimplerCancel(s: IOCancelBenchState): Unit = {
    val tsk = IOCancel.stoppableSimpler { stop =>
      s.cooperativeRelAcq(s.ref, stop)
    }
    s.runAndCancelTask(tsk, replicas = N)
  }

  @Benchmark
  def interruptibleFinish(s: IOCancelBenchState): Unit = {
    val tsk = s.interruptible(s.ref)
    s.runAndFinishTask(tsk, replicas = N)
  }

  @Benchmark
  def interruptibleCancel(s: IOCancelBenchState): Unit = {
    val tsk = s.interruptible(s.ref)
    s.runAndCancelTask(tsk, replicas = N)
  }
}

@State(Scope.Thread)
class IOCancelBenchState {

  final class MyException extends Exception

  val runtime =
    cats.effect.unsafe.IORuntime.global

  val ref =
    new AtomicReference[String]("x")

  final val length =
    100.microseconds

  final val checkInterval =
    16384

  def runAndCancelTask(task: IO[String], replicas: Int): Unit = {
    val t = task.start.flatMap { fiber =>
      IO.sleep(length) *> fiber.cancel *> IO { ref.set("x") }
    }.onCancel(IO { ref.set("x") }).onError(_ => IO { ref.set("x") })
    t.replicateA_(replicas).unsafeRunSync()(runtime)
  }

  def runAndFinishTask(task: IO[String], replicas: Int): Unit = {
    val t = task.start.flatMap { fiber =>
      IO.sleep(length) *> IO { ref.set("foo") } *> (
        fiber.joinWith(IO.pure("x")) *> IO { ref.set("x") }
      )
    }
    t.replicateA_(replicas).unsafeRunSync()(runtime)
  }

  def cooperative(ref: AtomicReference[String], stop: AtomicBoolean): IO[String] = {
    IO { goCooperative(count = 1, ref = ref, stop = stop).toString }.redeem(
      _ => "error",
      s => s,
    )
  }

  def cooperativeRelAcq(ref: AtomicReference[String], stop: AtomicBoolean): IO[String] = {
    IO { goCooperativeRelAcq(count = 1, ref = ref, stop = stop).toString }.redeem(
      _ => "error",
      s => s,
    )
  }

  def interruptible(ref: AtomicReference[String]): IO[String] = {
    IO.interruptible { goInterruptible(count = 1, ref = ref).toString }.redeem(
      _ => "error",
      s => s,
    )
  }

  @tailrec
  private[this] def goCooperative(count: Int, ref: AtomicReference[String], stop: AtomicBoolean): Int = {
    if (ref.compareAndSet("foo", "bar")) {
      count
    } else {
      maybeStop1(count, stop)
      Thread.onSpinWait()
      goCooperative(count = count + 1, ref = ref, stop = stop)
    }
  }

  private[this] def maybeStop1(count: Int, stop: AtomicBoolean): Unit = {
    if ((count % checkInterval) == 0) {
      checkStop1(stop)
    }
  }

  private[this] def checkStop1(stop: AtomicBoolean): Unit = {
    if (stop.get() || Thread.interrupted()) {
      throw new MyException
    }
  }

  @tailrec
  private[this] def goCooperativeRelAcq(count: Int, ref: AtomicReference[String], stop: AtomicBoolean): Int = {
    if (ref.compareAndSet("foo", "bar")) {
      count
    } else {
      maybeStop1RelAcq(count, stop)
      Thread.onSpinWait()
      goCooperativeRelAcq(count = count + 1, ref = ref, stop = stop)
    }
  }

  private[this] def maybeStop1RelAcq(count: Int, stop: AtomicBoolean): Unit = {
    if ((count % checkInterval) == 0) {
      checkStop1RelAcq(stop)
    }
  }

  private[this] def checkStop1RelAcq(stop: AtomicBoolean): Unit = {
    if (stop.getAcquire() || Thread.interrupted()) {
      throw new MyException
    }
  }

  @tailrec
  private[this] def goInterruptible(count: Int, ref: AtomicReference[String]): Int = {
    if (ref.compareAndSet("foo", "bar")) {
      count
    } else {
      maybeStop2(count)
      Thread.onSpinWait()
      goInterruptible(count = count + 1, ref = ref)
    }
  }

  private[this] def maybeStop2(count: Int): Unit = {
    if ((count % checkInterval) == 0) {
      checkStop2()
    }
  }

  private[this] def checkStop2(): Unit = {
    if (Thread.interrupted()) {
      throw new MyException
    }
  }
}
