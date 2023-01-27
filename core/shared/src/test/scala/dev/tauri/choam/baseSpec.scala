/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.TimeoutException

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._

import cats.effect.{ Sync, Async, IO, SyncIO, MonadCancel, Temporal }
import cats.effect.kernel.Outcome
import cats.effect.kernel.testkit.TestContext
import cats.effect.unsafe.{ IORuntime, IORuntimeConfig, Scheduler }

import munit.{ CatsEffectSuite, Location, FunSuite, FailException }

trait BaseSpecF[F[_]]
  extends FunSuite
  with MUnitUtils
  with cats.syntax.AllSyntax
  with cats.effect.syntax.AllSyntax { this: McasImplSpec =>

  implicit def rF: Reactive[F]

  implicit def mcF: MonadCancel[F, Throwable] =
    this.F

  /** Not implicit, so that `rF` is used for sure */
  def F: Sync[F]

  protected def absolutelyUnsafeRunSync[A](fa: F[A]): A

  def assumeF(cond: => Boolean, clue: String = "assumption failed")(implicit loc: Location): F[Unit] =
    F.delay { this.assume(cond, clue)(loc) }

  def assertF(cond: => Boolean, clue: String = "assertion failed")(implicit loc: Location): F[Unit] = {
    F.delay { this.assert(cond, clue) }
  }

  def assertEqualsF[A, B](obtained: A, expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit] = {
    F.delay { this.assertEquals[A, B](obtained, expected, clue) }
  }

  def assertNotEqualsF[A, B](obtained: A, expected: B, clue: String = "values are the same")(
    implicit loc: Location, ev: A =:= B
  ): F[Unit] = {
    F.delay { this.assertNotEquals[A, B](obtained, expected, clue) }
  }

  def assertSameInstanceF[A](
    obtained: A,
    expected: A,
    clue: String = "objects are not the same instance"
  )(implicit loc: Location): F[Unit] = F.delay {
    this.assertSameInstance(obtained, expected, clue)(loc)
  }

  def assertResultF[A, B](obtained: F[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit]

  def failF[A](clue: String = "assertion failed")(implicit loc: Location): F[A] = {
    F.flatMap(assertF(false, clue)) { _ =>
      F.raiseError[A](new IllegalStateException("unreachable code"))
    }
  }

  def assumeNotZio: F[Unit] = {
    this.assumeF(true) // ZIO spec must override
  }
}

trait BaseSpecAsyncF[F[_]] extends BaseSpecF[F] { this: McasImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Async[F]
  override implicit def mcF: Temporal[F] =
    this.F
  override implicit def rF: Reactive[F] =
    new Reactive.SyncReactive[F](this.mcasImpl)(this.F)
}

trait BaseSpecSyncF[F[_]] extends BaseSpecF[F] { this: McasImplSpec =>
  /** Not implicit, so that `rF` is used for sure */
  override def F: Sync[F]
  override implicit def rF: Reactive[F] =
    new Reactive.SyncReactive[F](this.mcasImpl)(F)
}

abstract class BaseSpecIO
  extends CatsEffectSuite
  with BaseSpecAsyncF[IO]
  with BaseSpecIOPlatform { this: McasImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Async[IO] =
    IO.asyncForIO

  final override def assertResultF[A, B](obtained: IO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): IO[Unit] = {
    assertIO(obtained, expected, clue)
  }

  override def munitIOTimeout: Duration =
    super.munitIOTimeout * 2

  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "IO",
      {
        case task: IO[a] =>
          // If we're close to `munitTimeout`, we'll
          // be killed soon anyway; so we're printing
          // a fiber dump, which may help diagnosing a
          // deadlock; after that, we're cancelling the
          // task:
          val dumpTimeout = this.munitTimeout match {
            case fd: FiniteDuration =>
              fd - 1.second // 1 second before the deadline
            case _: Duration.Infinite =>
              1.hour // whatever
          }
          task.timeoutTo(
            dumpTimeout,
            dumpFibers *> IO.raiseError(new TimeoutException(dumpTimeout.toString))
          ).unsafeToFuture()
      }
    ) +: super.munitValueTransforms
  }

  private[this] def dumpFibers: IO[Unit] = {
    import scala.language.reflectiveCalls
    type FiberMonitor = {
      def liveFiberSnapshot(print: String => Unit): Unit
    }
    type FmHolder = {
      val fiberMonitor: FiberMonitor
    }
    IO {
      this
        .munitIORuntime
        .asInstanceOf[FmHolder]
        .fiberMonitor
        .liveFiberSnapshot(System.err.print(_))
    }
  }
}

abstract class BaseSpecTickedIO extends BaseSpecIO with TestContextSpec[IO] { this: McasImplSpec =>

  protected override lazy val testContext: TestContext =
    TestContext()

  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "Ticked IO",
      { case task: IO[a] =>
        @volatile
        var res: Outcome[cats.Id, Throwable, a] = null
        task
          .flatMap(IO.pure)
          .handleErrorWith(IO.raiseError)
          .unsafeRunAsyncOutcome({ (outcome) => res = outcome })(this.tickedMunitIoRuntime)
        testContext.tickAll()
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

  final override def munitIoRuntime: IORuntime = {
    // Note: we're NOT returning the ticked runtime,
    // because MUnit will use this for its own stuff.
    this.dummyMunitIoRuntime
  }

  final override def munitIORuntime: IORuntime = {
    // See above
    this.munitIoRuntime
  }

  private[this] lazy val tickedMunitIoRuntime = {
    IORuntime(
      compute = testContext,
      blocking = testContext,
      scheduler = new Scheduler {
        override def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
          val cancel = testContext.schedule(delay, task)
          new Runnable {
            override def run(): Unit = cancel()
          }
        }
        override def nowMillis(): Long = {
          testContext.now().toMillis
        }
        override def monotonicNanos(): Long = {
          testContext.now().toNanos
        }
      },
      shutdown = () => {},
      config = IORuntimeConfig(),
    )
  }

  private[this] lazy val dummyMunitIoRuntime = {
    val dummyEc = new ExecutionContext {
      final override def execute(runnable: Runnable): Unit =
        runnable.run()
      final override def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace()
    }
    IORuntime(
      compute = dummyEc,
      blocking = dummyEc,
      scheduler = new Scheduler {
        override def sleep(delay: FiniteDuration, task: Runnable): Runnable =
          null
        override def nowMillis(): Long =
          0L
        override def monotonicNanos(): Long =
          0L
      },
      shutdown = () => {},
      config = IORuntimeConfig()
    )
  }
}

abstract class BaseSpecSyncIO extends CatsEffectSuite with BaseSpecSyncF[SyncIO] { this: McasImplSpec =>

  /** Not implicit, so that `rF` is used for sure */
  final override def F: Sync[SyncIO] =
    SyncIO.syncForSyncIO

  protected final override def absolutelyUnsafeRunSync[A](fa: SyncIO[A]): A =
    fa.unsafeRunSync()

  final override def assertResultF[A, B](obtained: SyncIO[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): SyncIO[Unit] = {
    assertSyncIO(obtained, expected, clue)
  }
}

trait TestContextSpec[F[_]] { this: BaseSpecAsyncF[F] with McasImplSpec =>

  protected def testContext: TestContext

  def tickAll: F[Unit] = F.delay {
    this.testContext.tickAll()
  }
}
