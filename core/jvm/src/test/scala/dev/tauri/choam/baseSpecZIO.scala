/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
import java.time.{ OffsetDateTime, Instant, LocalDateTime, ZoneId, Clock => JClock }
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import cats.effect.kernel.Async
import cats.effect.kernel.testkit.TestContext

import munit.{ CatsEffectSuite, Location }

trait UtilsForZIO { this: BaseSpecAsyncF[zio.Task] with McasImplSpec =>

  final override def assertResultF[A, B](obtained: zio.Task[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): zio.Task[Unit] = {
    obtained.flatMap(ob => zio.ZIO.attempt { this.assertEquals(ob, expected, clue) })
  }

  final override def assumeNotZio: zio.Task[Unit] = {
    this.assumeF(false)
  }
}

abstract class BaseSpecZIO
  extends CatsEffectSuite
  with BaseSpecAsyncF[zio.Task]
  with UtilsForZIO { this: McasImplSpec =>

  private[this] val runtime =
    zio.Runtime.default

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncRuntimeInstance(this.runtime)

  protected final override def absolutelyUnsafeRunSync[A](fa: zio.Task[A]): A = {
    zio.Unsafe.unsafe { implicit u =>
      this.runtime.unsafe.run(fa).getOrThrow()
    }
  }

  private def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        zio.Unsafe.unsafe { implicit u =>
          this.runtime.unsafe.runToFuture(tsk)
        }
      }
    )
  }

  override def munitValueTransforms: List[this.ValueTransform] = {
    super.munitValueTransforms :+ this.transformZIO
  }

  override def munitIgnore: Boolean = {
    super.munitIgnore || this.isOpenJ9()
  }
}

abstract class BaseSpecTickedZIO
  extends CatsEffectSuite
  with TestContextSpec[zio.Task]
  with BaseSpecAsyncF[zio.Task]
  with UtilsForZIO { this: McasImplSpec =>

  import zio._

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncRuntimeInstance(this.zioRuntime)

  protected final override def absolutelyUnsafeRunSync[A](fa: zio.Task[A]): A = {
    zio.Unsafe.unsafe { implicit u =>
      this.zioRuntime.unsafe.run(fa).getOrThrow()
    }
  }

  override def munitValueTransforms: List[this.ValueTransform] = {
    super.munitValueTransforms :+ this.transformZIO
  }

  override def munitIgnore: Boolean = {
    super.munitIgnore || this.isOpenJ9()
  }

  private def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "Ticked ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        val fut = zio.Unsafe.unsafe { implicit u =>
          this.zioRuntime.unsafe.runToFuture(tsk)
        }
        testContext.tickAll()
        fut
      }
    )
  }

  protected override lazy val testContext: TestContext =
    TestContext()

  private[this] var initializing =
    true

  private lazy val zioRuntime: Runtime[Any] = {

    val testContextExecutor: zio.Executor = {
      zio.Executor.fromExecutionContext(new ExecutionContext {
        def execute(runnable: Runnable): Unit = {
          testContext.execute(runnable)
          if (initializing) {
            // This is a hack to avoid deadlock
            // when creating the runtime itself:
            testContext.tickAll()
          }
        }
        def reportFailure(cause: Throwable): Unit =
          testContext.reportFailure(cause)
      })
    }

    val testContextBlockingExecutor: zio.Executor =
      zio.Executor.fromExecutionContext(testContext.deriveBlocking())

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

      override def schedule(x: Runnable, y: Long, z: TimeUnit): ScheduledFuture[_ <: Object] = {
        throw new NotImplementedError("schedule(Runnable, Long, TimeUnit)")
      }

      override def schedule[V <: Object](x: Callable[V], y: Long, z: TimeUnit): ScheduledFuture[V] = {
        throw new NotImplementedError("schedule(Callable, Long, TimeUnit)")
      }

      override def scheduleAtFixedRate(x: Runnable, y: Long, z: Long, zz: TimeUnit): ScheduledFuture[_ <: Object] = {
        throw new NotImplementedError("scheduleAtFixedRate(Runnable, Long, Long, TimeUnit)")
      }

      override def scheduleWithFixedDelay(x: Runnable, y: Long, z: Long, zz: TimeUnit): ScheduledFuture[_ <: Object] = {
        throw new NotImplementedError("scheduleWithFixedDelay(Runnable, Long, Long, TimeUnit)")
      }
    })

    val myClock = new Clock { self =>

      private[this] final val zone =
        ZoneId.of("UTC")

      override def javaClock(implicit trace: Trace): UIO[JClock] = {
        ZIO.succeed {
          new JClock {
            override def getZone(): ZoneId =
              self.zone
            override def withZone(z: ZoneId): JClock =
              throw new NotImplementedError("withZone(ZoneId)")
            override def instant(): Instant = {
              val now = testContext.now()
              Instant.ofEpochMilli(now.toMillis)
            }
          }
        }
      }

      override def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] = {
        this.instant.map { inst =>
          unit.convert(inst.toEpochMilli, TimeUnit.MILLISECONDS)
        }
      }

      override def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] = {
        this.instant.map { inst =>
          unit.between(java.time.Instant.EPOCH, inst)
        }
      }

      override def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] = {
        this.instant.map { inst =>
          OffsetDateTime.ofInstant(inst, zone)
        }
      }

      override def instant(implicit trace: Trace): UIO[Instant] = ZIO.succeed {
        val now = testContext.now()
        Instant.ofEpochMilli(now.toMillis)
      }

      override def localDateTime(implicit trace: Trace): UIO[LocalDateTime] = {
        this.instant.map { inst =>
          LocalDateTime.ofInstant(inst, zone)
        }
      }

      override def nanoTime(implicit trace: Trace): UIO[Long] = ZIO.succeed {
        testContext.now().toNanos
      }

      override def scheduler(implicit trace: Trace): UIO[Scheduler] =
        ZIO.succeed(myScheduler)

      override def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] = {
        val finDur = FiniteDuration(duration.toNanos(), "ns")
        ZIO.asyncInterrupt[Any, Nothing, Unit] { cb =>
          val cancel = testContext.schedule(
            finDur,
            () => { cb(ZIO.succeed(())) }
          )
          Left(ZIO.succeed(cancel()))
        }
      }
    }

    val res: Runtime.Scoped[Unit] = zio.Unsafe.unsafe { implicit u =>
      Runtime.unsafe.fromLayer(
        ZLayer
          .scoped[Any](ZIO.withClockScoped(myClock))
          .and(Runtime.setExecutor(testContextExecutor))
          .and(Runtime.setBlockingExecutor(testContextBlockingExecutor))
      )
    }
    initializing = false
    res
  }
}
