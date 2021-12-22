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

import java.{ util => ju }
import java.util.Collection
import java.util.concurrent.{ TimeUnit, Callable, Future, ScheduledFuture, ScheduledExecutorService }
import java.time.{ OffsetDateTime, Instant, LocalDateTime, ZoneId }

import scala.concurrent.duration._

import cats.effect.kernel.Async
import cats.effect.kernel.testkit.TestContext

import munit.{ CatsEffectSuite, Location }

trait UtilsForZIO { this: BaseSpecAsyncF[zio.Task] with KCASImplSpec =>

  final override def assertResultF[A, B](obtained: zio.Task[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): zio.Task[Unit] = {
    obtained.flatMap(ob => zio.Task { this.assertEquals(ob, expected, clue) })
  }
}

abstract class BaseSpecZIO
  extends CatsEffectSuite
  with BaseSpecAsyncF[zio.Task]
  with UtilsForZIO { this: KCASImplSpec =>

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncRuntimeInstance(zio.Runtime.default)

  private def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        // TODO: this will fail if it's not really a Task
        zio.Runtime.default.unsafeRunToFuture(tsk)
      }
    )
  }

  override def munitValueTransforms: List[this.ValueTransform] = {
    super.munitValueTransforms :+ this.transformZIO
  }
}

abstract class BaseSpecTickedZIO
  extends CatsEffectSuite
  with TestContextSpec[zio.Task]
  with BaseSpecAsyncF[zio.Task]
  with UtilsForZIO { this: KCASImplSpec =>

  import zio._

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncRuntimeInstance(this.zioRuntime)

  override def munitValueTransforms: List[this.ValueTransform] = {
    super.munitValueTransforms :+ this.transformZIO
  }

  private def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "Ticked ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        // TODO: this will fail if it's not really a Task
        val fut = this.zioRuntime.unsafeRunToFuture(tsk)
        testContext.tickAll()
        fut
      }
    )
  }

  protected override val testContext: TestContext =
    TestContext()

  private lazy val zioRuntime: Runtime[ZEnv] = {

    val testContextExecutor: zio.Executor =
      zio.Executor.fromExecutionContext(32)(testContext.derive())

    val testContextBlockingExecutor: zio.Executor =
      zio.Executor.fromExecutionContext(32)(testContext.deriveBlocking())

    val myScheduler = Scheduler.fromScheduledExecutorService(new ScheduledExecutorService {

      override def execute(x: Runnable): Unit =
        testContextExecutor.asExecutionContextExecutorService.execute(x)

      override def shutdown(): Unit =
        ()

      override def shutdownNow(): ju.List[Runnable] =
        ju.List.of()

      override def isShutdown(): Boolean =
        false

      override def isTerminated(): Boolean =
        false

      override def awaitTermination(x: Long, y: TimeUnit): Boolean =
        false

      override def submit[T <: Object](x: Callable[T]): Future[T] =
        testContextExecutor.asExecutionContextExecutorService.submit(x)

      override def submit[T <: Object](x: Runnable, y: T): Future[T] =
        testContextExecutor.asExecutionContextExecutorService.submit(x, y)

      override def submit(x: Runnable): Future[_ <: Object] =
        testContextExecutor.asExecutionContextExecutorService.submit(x)

      override def invokeAll[T <: Object](x: Collection[_ <: Callable[T]]): ju.List[Future[T]] =
        testContextExecutor.asExecutionContextExecutorService.invokeAll(x)

      override def invokeAll[T <: Object](x: Collection[_ <: Callable[T]], y: Long, z: TimeUnit): ju.List[Future[T]] =
        testContextExecutor.asExecutionContextExecutorService.invokeAll(x, y, z)

      override def invokeAny[T <: Object](x: Collection[_ <: Callable[T]]): T =
        testContextExecutor.asExecutionContextExecutorService.invokeAny(x)

      override def invokeAny[T <: Object](x: Collection[_ <: Callable[T]], y: Long, z: TimeUnit): T =
        testContextExecutor.asExecutionContextExecutorService.invokeAny(x, y, z)

      override def schedule(x: Runnable, y: Long, z: TimeUnit): ScheduledFuture[_ <: Object] =
        throw new NotImplementedError("schedule(Runnable, Long, TimeUnit)")

      override def schedule[V <: Object](x: Callable[V], y: Long, z: TimeUnit): ScheduledFuture[V] =
        throw new NotImplementedError("schedule(Callable, Long, TimeUnit)")

      override def scheduleAtFixedRate(x: Runnable, y: Long, z: Long, zz: TimeUnit): ScheduledFuture[_ <: Object] =
        throw new NotImplementedError("scheduleAtFixedRate(Runnable, Long, Long, TimeUnit)")

      override def scheduleWithFixedDelay(x: Runnable, y: Long, z: Long, zz: TimeUnit): ScheduledFuture[_ <: Object] =
        throw new NotImplementedError("scheduleWithFixedDelay(Runnable, Long, Long, TimeUnit)")
    })

    val myClock = new Clock {

      private[this] final val zone =
        ZoneId.of("UTC")

      override def currentTime(unit: => TimeUnit)(implicit trace: ZTraceElement): UIO[Long] = {
        this.instant.map { inst =>
          unit.convert(inst.toEpochMilli, TimeUnit.MILLISECONDS)
        }
      }

      override def currentDateTime(implicit trace: ZTraceElement): UIO[OffsetDateTime] = {
        this.instant.map { inst =>
          OffsetDateTime.ofInstant(inst, zone)
        }
      }

      override def instant(implicit trace: ZTraceElement): UIO[Instant] = UIO.succeed {
        val now = testContext.now()
        Instant.ofEpochMilli(now.toMillis)
      }

      override def localDateTime(implicit trace: ZTraceElement): UIO[LocalDateTime] = {
        this.instant.map { inst =>
          LocalDateTime.ofInstant(inst, zone)
        }
      }

      override def nanoTime(implicit trace: ZTraceElement): UIO[Long] = UIO.succeed {
        testContext.now().toNanos
      }

      override def scheduler(implicit trace: ZTraceElement): UIO[Scheduler] =
        UIO.succeed(myScheduler)

      override def sleep(duration: => Duration)(implicit trace: ZTraceElement): UIO[Unit] = {
        val finDur = FiniteDuration(duration.toNanos(), "ns")
        // println(s"sleep(${finDur})")
        UIO.asyncInterrupt[Unit] { cb =>
          val cancel = testContext.schedule(
            finDur,
            () => { cb(UIO.succeed(())) }
          )
          Left(UIO.succeed(cancel()))
        }
      }
    }

    Runtime
      .default
      .withExecutor(testContextExecutor)
      .withBlockingExecutor(testContextBlockingExecutor)
      .map { env => env.update[Clock](_ => myClock) }
  }
}
