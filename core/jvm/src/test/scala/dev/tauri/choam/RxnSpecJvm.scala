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

import java.util.concurrent.CountDownLatch

import scala.concurrent.duration._

import cats.effect.IO

final class RxnSpec_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RxnSpecJvm[IO]

final class RxnSpec_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with RxnSpecJvm[zio.Task]

final class RxnSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnSpecJvm[IO]

final class RxnSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RxnSpecJvm[zio.Task]

trait RxnSpecJvm[F[_]] extends RxnSpec[F] { this: KCASImplSpec =>

  test("Thread interruption in infinite retry") {
    val never = Rxn.unsafe.retry[Any, Unit]
    @volatile var exception = Option.empty[Throwable]
    F.blocking {
      val cdl = new CountDownLatch(1)
      val t = new Thread(() => {
        cdl.countDown()
        never.unsafeRun(this.kcasImpl)
      })
      t.setUncaughtExceptionHandler((_, ex) => {
        if (!ex.isInstanceOf[InterruptedException]) {
          // ignore interrupt, otherwise fail the test
          exception = Some(ex)
        }
      })
      t.start()
      assert(t.isAlive())
      cdl.await()
      Thread.sleep(1000L)
      assert(t.isAlive())
      t.interrupt()
      var c = 0
      while (t.isAlive() && (c < 2000)) {
        c += 1
        Thread.sleep(1L)
      }
      assert(!t.isAlive())
      exception.foreach(throw _)
    }
  }

  test("Thread interruption with alternatives") {
    val ref = Ref.unsafe("a")
    val never: Axn[Unit] = (1 to 1000).foldLeft(ref.unsafeCas("foo", "bar")) { (acc, num) =>
      acc + ref.unsafeCas(ov = num.toString(), nv = "foo")
    }
    @volatile var exception = Option.empty[Throwable]
    F.blocking {
      val cdl = new CountDownLatch(1)
      val t = new Thread(() => {
        cdl.countDown()
        never.unsafeRun(this.kcasImpl)
      })
      t.setUncaughtExceptionHandler((_, ex) => {
        if (!ex.isInstanceOf[InterruptedException]) {
          // ignore interrupt, otherwise fail the test
          exception = Some(ex)
        }
      })
      t.start()
      assert(t.isAlive())
      cdl.await()
      Thread.sleep(1000L)
      assert(t.isAlive())
      t.interrupt()
      var c = 0
      while (t.isAlive() && (c < 2000)) {
        c += 1
        Thread.sleep(1L)
      }
      assert(!t.isAlive())
      exception.foreach(throw _)
    }
  }

  test("Autoboxing (JVM)") {
    // Integers between (typically) -128 and 127 are
    // cached. Due to autoboxing, other integers may
    // seem to change their "identity".
    val n = 9999999
    for {
      _ <- F.delay {
        // check the assumption above:
        assertIntIsNotCached(n)
      }
      ref <- Ref[Int](n).run[F]
      // `update` works fine:
      _ <- ref.update(_ + 1).run[F]
      _ <- assertResultF(ref.get.run[F], n + 1)
      // `unsafeInvisibleRead` then `unsafeCas` doesn't:
      unsafeRxn = ref.unsafeInvisibleRead.flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          ref.unsafeCas(ov = v, nv = v + 1)
        }
      }
      fib <- F.interruptible {
        unsafeRxn.unsafePerform((), this.kcasImpl)
      }.start
      _ <- F.sleep(0.5.second)
      _ <- fib.cancel
      _ <- assertResultF(ref.get.run[F], n + 1) // no change
      // but it *seems* to work with small numbers:
      _ <- ref.getAndSet[F](42)
      _ <- unsafeRxn.run[F]
      _ <- assertResultF(ref.get.run[F], 43)
    } yield ()
  }
}
