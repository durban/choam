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

import java.util.concurrent.TimeUnit
import java.time.{ DateTimeException, OffsetDateTime }

import scala.concurrent.duration._

import cats.effect.kernel.Async
import cats.effect.kernel.testkit.TestContext

import munit.{ CatsEffectSuite, Location }

abstract class BaseSpecZIO extends CatsEffectSuite with BaseSpecAsyncF[zio.Task] { this: KCASImplSpec =>

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncRuntimeInstance(zio.Runtime.default)

  final override def assertResultF[A, B](obtained: zio.Task[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): zio.Task[Unit] = {
    obtained.flatMap(ob => zio.Task { this.assertEquals(ob, expected, clue) })
  }

  protected def transformZIO: ValueTransform = {
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

abstract class BaseSpecTickedZIO extends BaseSpecZIO with TestContextSpec[zio.Task] { this: KCASImplSpec =>

  import zio._
  import zio.clock.Clock
  import zio.console.Console
  import zio.system.System
  import zio.random.Random
  import zio.blocking.Blocking
  import zio.internal.Executor

  protected override val testContext: TestContext =
    TestContext()

  private val testContextExecutor: zio.internal.Executor = new Executor {
    override def yieldOpCount: Int =
      Int.MaxValue
    override def metrics =
      None
    override def submit(runnable: Runnable): Boolean = {
      testContext.execute(runnable)
      true
    }
  }

  private val zioRuntime: zio.Runtime[zio.ZEnv] = {
    zio.Runtime(
      Has.allOf[Clock.Service, Console.Service, System.Service, Random.Service, Blocking.Service](
        new Clock.Service {
          override def currentTime(unit: TimeUnit): UIO[Long] =
            UIO.effectTotal { testContext.now().toUnit(unit).toLong }
          override def currentDateTime: zio.IO[DateTimeException, OffsetDateTime] =
            zio.IO.effect { OffsetDateTime.now() }.catchAll { (ex: Throwable) =>
              ex match {
                case dte: DateTimeException => zio.IO.fail(dte)
                case _ => zio.IO.die(ex)
              }
            }
          override def nanoTime: UIO[Long] =
            UIO.effectTotal { testContext.now().toNanos }
          override def sleep(duration: zio.duration.Duration): UIO[Unit] = {
            UIO.effectTotal {
              testContext.schedule(
                FiniteDuration(duration.toNanos(), "ns"),
                new Runnable { override def run(): Unit = () }
              )
              ()
            }
          }
        },
        Console.Service.live,
        System.Service.live,
        Random.Service.live,
        new Blocking.Service {
          override def blockingExecutor: Executor =
            testContextExecutor
        }
      ),
      zio.internal.Platform.fromExecutionContext(testContext),
    )
  }

  protected override def transformZIO: ValueTransform = {
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
}
