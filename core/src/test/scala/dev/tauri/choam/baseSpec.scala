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

import scala.concurrent.Future
import scala.concurrent.duration._

import cats.effect.{ Sync, Async, IO, SyncIO, MonadCancel, Temporal }
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntime

import munit.{ CatsEffectSuite, Location, FunSuite, FailException }

trait MUnitUtils { this: FunSuite =>

  def assertSameInstance[A](
    obtained: A,
    expected: A,
    clue: String = "objects are not the same instance"
  )(implicit loc: Location): Unit = {
    assert(equ(this.clue(obtained), this.clue(expected)), clue)
  }
}

trait BaseSpecA
  extends FunSuite
  with MUnitUtils

trait BaseSpecF[F[_]]
  extends FunSuite
  with MUnitUtils
  with cats.syntax.AllSyntax
  with cats.effect.syntax.AllSyntax { this: KCASImplSpec =>

  implicit def rF: Reactive[F]

  implicit def mcF: MonadCancel[F, Throwable] =
    this.F

  /** Not implicit, so that `rF` is used for sure */
  def F: Sync[F]

  def assertF(cond: Boolean, clue: String = "assertion failed")(implicit loc: Location): F[Unit] = {
    F.delay { this.assert(cond, clue) }
  }

  def assertEqualsF[A, B](obtained: A, expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit] = {
    F.delay { this.assertEquals[A, B](obtained, expected, clue) }
  }

  def assertResultF[A, B](obtained: F[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit]

  def failF[A](clue: String = "assertion failed")(implicit loc: Location): F[A] = {
    F.flatMap(assertF(false, clue)) { _ =>
      F.raiseError[A](new IllegalStateException("unreachable code"))
    }
  }
}

trait BaseSpecAsyncF[F[_]] extends BaseSpecF[F] { this: KCASImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Async[F]
  override implicit def mcF: Temporal[F] =
    this.F
  override implicit def rF: Reactive.Async[F] =
    new Reactive.AsyncReactive[F](this.kcasImpl)(this.F)
}

trait BaseSpecSyncF[F[_]] extends BaseSpecF[F] { this: KCASImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Sync[F]
  override implicit def rF: Reactive[F] =
    new Reactive.SyncReactive[F](this.kcasImpl)(F)
}

abstract class BaseSpecIO extends CatsEffectSuite with BaseSpecAsyncF[IO] { this: KCASImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Async[IO] =
    IO.asyncForIO

  final override def assertResultF[A, B](obtained: IO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): IO[Unit] = {
    assertIO(obtained, expected, clue)
  }
}

abstract class BaseSpecTickedIO extends BaseSpecIO with TestContextSpec[IO] { this: KCASImplSpec =>

  protected override val testControl: TestControl =
    TestControl()

  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "Ticked IO",
      { case task: IO[a] =>
        @volatile
        var res: Outcome[cats.Id, Throwable, a] = null
        task
          .flatMap(IO.pure)
          .handleErrorWith(IO.raiseError)
          .unsafeRunAsyncOutcome({ (outcome) => res = outcome })(this.ioRuntime)
        testControl.tickAll()
        if (res eq null) {
          Future.failed(new FailException("ticked IO didn't complete", Location.empty))
        } else {
          res.fold(
            canceled = Future.failed(new FailException("ticked IO was cancelled", Location.empty)),
            errored = Future.failed(_),
            completed = Future.successful(_),
          )
        }
      }
    ) +: super.munitValueTransforms
  }

  override implicit val ioRuntime: IORuntime =
    this.testControl.runtime
}

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

  protected override val testControl: TestControl =
    TestControl()

  private val testContextExecutor: zio.internal.Executor = new Executor {
    override def yieldOpCount: Int =
      Int.MaxValue
    override def metrics =
      None
    override def submit(runnable: Runnable): Boolean = {
      testControl.runtime.compute.execute(runnable)
      true
    }
  }

  private val zioRuntime: zio.Runtime[zio.ZEnv] = {
    zio.Runtime(
      Has.allOf[Clock.Service, Console.Service, System.Service, Random.Service, Blocking.Service](
        new Clock.Service {
          override def currentTime(unit: TimeUnit): UIO[Long] =
            UIO.effectTotal { unit.convert(testControl.runtime.scheduler.nowMillis(), TimeUnit.MILLISECONDS) }
          override def currentDateTime: zio.IO[DateTimeException, OffsetDateTime] =
            zio.IO.effect { OffsetDateTime.now() }.catchAll { (ex: Throwable) =>
              ex match {
                case dte: DateTimeException => zio.IO.fail(dte)
                case _ => zio.IO.die(ex)
              }
            }
          override def nanoTime: UIO[Long] =
            this.currentTime(TimeUnit.NANOSECONDS)
          override def sleep(duration: zio.duration.Duration): UIO[Unit] = {
            UIO.effectTotal {
              testControl.runtime.scheduler.sleep(
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
      zio.internal.Platform.fromExecutionContext(testControl.runtime.compute),
    )
  }

  protected override def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "Ticked ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        // TODO: this will fail if it's not really a Task
        val fut = this.zioRuntime.unsafeRunToFuture(tsk)
        testControl.tickAll()
        fut
      }
    )
  }
}

abstract class BaseSpecSyncIO extends CatsEffectSuite with BaseSpecSyncF[SyncIO] { this: KCASImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Sync[SyncIO] =
    SyncIO.syncForSyncIO

  final override def assertResultF[A, B](obtained: SyncIO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): SyncIO[Unit] = {
    assertSyncIO(obtained, expected, clue)
  }
}

trait TestContextSpec[F[_]] { this: BaseSpecAsyncF[F] with KCASImplSpec =>

  protected def testControl: TestControl

  def tickAll: F[Unit] = F.delay {
    this.testControl.tickAll()
  }
}
