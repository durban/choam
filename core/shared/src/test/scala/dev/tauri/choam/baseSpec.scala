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

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._

import cats.effect.{ Sync, Async, IO, SyncIO }
import cats.effect.kernel.Outcome
import cats.effect.kernel.testkit.TestContext
import cats.effect.unsafe.{ IORuntime, IORuntimeConfig, Scheduler }

import core.{ Rxn, Reactive, AsyncReactive }
import core.RetryStrategy.Internal.Stepper

import munit.{ CatsEffectSuite, Location, FunSuite, FailException }

trait BaseSpecF[F[_]]
  extends FunSuite
  with MUnitUtils
  with cats.syntax.AllSyntax
  with cats.effect.syntax.AllSyntax
  with BaseSpecFPlatform { this: McasImplSpec =>

  implicit def rF: Reactive[F]

  implicit def F: Sync[F]

  protected def absolutelyUnsafeRunSync[A](fa: F[A]): A

  private[this] val rtHolder: AtomicReference[ChoamRuntime] =
    new AtomicReference

  /** Lazily initialized `ChoamRuntime` which uses `this.mcasImpl` */
  protected final def runtime: ChoamRuntime = {
    rtHolder.get() match {
      case null =>
        // Note: we'll never close this runtime, but
        // that's fine, because `McasImplSpec` will
        // close the MCAS in `afterAll`, and the
        // OsRng we use is a global one which lives
        // forever in the `BaseSpec` companion object.
        val rt = ChoamRuntime.forTesting(this.mcasImpl)
        val wit = this.compareAndExchange(rtHolder, null, rt)
        if (wit eq null) {
          rt
        } else {
          wit
        }
      case rt =>
        rt
    }
  }

  def assumeF(cond: => Boolean, clue: String = "assumption failed")(implicit loc: Location): F[Unit] =
    F.delay { this.assume(cond, clue)(using loc) }

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
    this.assertSameInstance(obtained, expected, clue)(using loc)
  }

  def assertResultF[A, B](obtained: F[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): F[Unit]

  def assertRaisesF[A](obtained: F[A], matcher: Throwable => Boolean)(implicit loc: Location): F[Unit] = {
    obtained.attempt.flatMap {
      case Left(e) =>
        F.delay(matcher(e)).flatMap { ok  => if (ok) F.unit else failF[Unit](s"assertRaisesF: errored with ${e}") }
      case Right(fa) =>
        failF(s"assertRaisesF: succeeded with ${fa}")
    }
  }

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

  override implicit def F: Async[F]

  override implicit def rF: AsyncReactive[F] =
    new AsyncReactive.AsyncReactiveImpl[F](this.mcasImpl)(using this.F)
}

trait BaseSpecSyncF[F[_]] extends BaseSpecF[F] { this: McasImplSpec =>

  override implicit def F: Sync[F]

  override implicit def rF: Reactive[F] =
    new Reactive.SyncReactive[F](this.mcasImpl)(using F)
}

/** Yeah, so this indirection exists so dotty doesn't crash... */
abstract class CatsEffectSuiteIndirection extends CatsEffectSuite {
  final override def munitIOTimeout: Duration =
    super.munitIOTimeout * 2
}

abstract class BaseSpecIO
  extends CatsEffectSuiteIndirection
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

  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "IO",
      {
        case task: IO[_] =>
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
          .unsafeRunAsyncOutcome({ (outcome) => res = outcome })(using this.tickedMunitIoRuntime)
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
      blocking = testContext.deriveBlocking(),
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
      config = IORuntimeConfig(
        // artificially low values, to stress
        // test behavior with auto-ceding:
        cancelationCheckThreshold = 1,
        autoYieldThreshold = 2,
      ),
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

trait TestContextSpec[F[_]] { self: BaseSpecAsyncF[F] & McasImplSpec =>

  protected def testContext: TestContext

  final def tickAll: F[Unit] = F.delay {
    this.testContext.tickAll()
  }

  final def tick: F[Unit] = F.delay {
    this.testContext.tick()
  }

  final def advanceAndTick(d: FiniteDuration): F[Unit] = F.delay {
    this.testContext.advanceAndTick(d)
  }

  final def mkStepper: F[Stepper[F]] = {
    Stepper[F](using F)
  }

  implicit final class StepperSyntax(stepper: Stepper[F]) {

    final def stepAndTickAll: F[Unit] = {
      stepper.step *> self.tickAll
    }

    final def run[A, B](r: Rxn[B]): F[B] = {
      r.performWithStepper(self.mcasImpl, stepper)(using F)
    }
  }
}
