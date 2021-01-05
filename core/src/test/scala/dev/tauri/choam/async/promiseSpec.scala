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
package async

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.kernel.Outcome

class PromiseSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with PromiseSpec[IO]

class PromiseSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with PromiseSpec[IO]

trait PromiseSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("Completing an empty promise should call all registered callbacks") {
    for {
      p <- Promise[Int].run[F]
      act = p.get[F]
      fib1 <- act.start
      fib2 <- act.start
      _ <- F.sleep(0.1.seconds)
      b <- (React.pure(42) >>> p.tryComplete).run[F]
      res1 <- fib1.joinWithNever
      res2 <- fib2.joinWithNever
      _ <- assertF(b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
    } yield ()
  }

  test("Completing a fulfilled promise should not be possible") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.tryComplete[F](42), true)
      _ <- assertResultF(p.tryComplete[F](42), false)
      _ <- assertResultF(p.tryComplete[F](99), false)
    } yield ()
  }

  test("Completing a fulfilled promise should not call any callbacks") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.tryComplete[F](42), true)
      cnt <- F.delay { new AtomicLong(0L) }
      act = p.get[F].map { x =>
        cnt.incrementAndGet()
        x
      }
      res1 <- act
      res2 <- act
      b <- (React.pure(42) >>> p.tryComplete).run[F]
      _ <- assertF(!b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
      _ <- assertResultF(F.delay { cnt.get() }, 2L)
    } yield ()
  }

  test("A cancelled callback should not be called") {
    @volatile var flag1 = false
    @volatile var flag2 = false
    for {
      p <- Promise[Int].run[F]
      f1 <- p.get[F].flatTap(_ => F.delay { flag1 = true }).start
      f2 <- p.get[F].flatTap(_ => F.delay { flag2 = true }).start
      _ <- F.sleep(0.1.seconds)
      _ <- f1.cancel
      ok <- p.tryComplete[F](42)
      _ <- assertF(ok)
      _ <- assertResultF(f2.joinWithNever, 42)
      _ <- assertResultF(f1.join, Outcome.canceled[F, Throwable, Int])
      _ <- assertResultF(F.delay { flag1 }, false)
      _ <- assertResultF(F.delay { flag2 }, true)
    } yield ()
  }

  test("Calling the callback should be followed by a thread shift") {
    @volatile var stop = false
    for {
      p <- Promise[Int].run[F]
      f <- p.get[F].map { v =>
        while (!stop) Thread.onSpinWait()
        v + 1
      }.start
      ok <- p.tryComplete(42)
      // now the fiber spins, hopefully on some other thread
      _ <- assertF(ok)
      _ <- F.sleep(0.1.seconds)
      _ <- F.delay { stop = true }
      _ <- f.joinWithNever
    } yield ()
  }

  test("Promise#get should be rerunnable") {
    for {
      p <- Promise[Int].run[F]
      l1 <- CountDownLatch[F](1)
      l2 <- CountDownLatch[F](1)
      g = p.get[F]
      f1 <- (l1.release >> g).start
      f2 <- (l2.release >> g).start
      _ <- l1.await
      ok <- p.tryComplete(42)
      _ <- l2.await
      _ <- assertF(ok)
      _ <- assertResultF(g, 42)
      _ <- assertResultF(g, 42)
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- assertResultF(f2.joinWithNever, 42)
    } yield ()
  }
}
