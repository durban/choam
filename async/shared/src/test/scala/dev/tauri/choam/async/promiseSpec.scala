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
package async

import java.util.concurrent.atomic.AtomicLong

import cats.{ Functor, Invariant, Contravariant }
import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.kernel.Outcome

import core.{ Rxn, Ref }

final class PromiseSpec_ThreadConfinedMcas_IO_Real
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with PromiseSpec[IO]

final class PromiseSpec_ThreadConfinedMcas_IO_Ticked
  extends BaseSpecTickedIO
  with SpecThreadConfinedMcas
  with PromiseSpecTicked[IO]

trait PromiseSpecTicked[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  test("Completing an empty promise should call all registered callbacks") {
    completeEmpty((i, p) => p.complete(i).run[F])
  }

  private def completeEmpty(doComplete: (Int, Promise[Int]) => F[Boolean]): F[Unit] = {
    for {
      p <- Promise[Int].run[F]
      act = p.get
      fib1 <- act.start
      fib2 <- act.start
      _ <- this.tickAll
      b <- doComplete(42, p)
      res1 <- fib1.joinWithNever
      res2 <- fib2.joinWithNever
      _ <- assertF(b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
    } yield ()
  }

  test("A cancelled callback should not be called") {
    @volatile var flag1 = false
    @volatile var flag2 = false
    for {
      p <- Promise[Int].run[F]
      f1 <- F.uncancelable { poll => poll(p.get).flatTap(_ => F.delay { flag1 = true }) }.start
      f2 <- F.uncancelable { poll => poll(p.get).flatTap(_ => F.delay { flag2 = true }) }.start
      _ <- this.tickAll
      _ <- f1.cancel
      ok <- p.complete(42).run[F]
      _ <- assertF(ok)
      _ <- assertResultF(f2.joinWithNever, 42)
      _ <- assertResultF(f1.join, Outcome.canceled[F, Throwable, Int])
      _ <- assertResultF(F.delay { flag1 }, false)
      _ <- assertResultF(F.delay { flag2 }, true)
    } yield ()
  }

  test("Promise#unsafeComplete") {
    for {
      // no subscribers:
      p1 <- Promise[Int].run[F]
      _ <- assertResultF(Rxn.unsafe.embedUnsafe { implicit ir =>
        p1.unsafeComplete(42)
      }.run[F], true)
      _ <- assertResultF(p1.tryGet.run, Some(42))
      _ <- assertResultF(Rxn.unsafe.embedUnsafe { implicit ir =>
        p1.unsafeComplete(99)
      }.run[F], false)
      _ <- assertResultF(p1.tryGet.run, Some(42))
      // has subscribers:
      p2 <- Promise[Int].run[F]
      fib1 <- p2.get[F].start
      _ <- this.tickAll
      fib2 <- p2.get[F].start
      _ <- this.tickAll
      _ <- assertResultF(Rxn.unsafe.embedUnsafe { implicit ir =>
        p2.unsafeComplete(42)
      }.run[F], true)
      _ <- assertResultF(Rxn.unsafe.embedUnsafe { implicit ir =>
        p2.unsafeComplete(99)
      }.run[F], false)
      _ <- assertResultF(fib1.joinWithNever, 42)
      _ <- assertResultF(fib2.joinWithNever, 42)
    } yield ()
  }

  test("Promise.unsafeNew") {
    for {
      pp <- Rxn.unsafe.embedUnsafe { implicit ir =>
        (Promise.unsafeNew[Int](), Promise.unsafeNew[String](Ref.AllocationStrategy.Padded))
      }.run[F]
      (p1, p2) = pp
      fib1 <- p1.get.start
      fib2 <- p2.get.start
      _ <- this.tickAll
      _ <- p1.complete(42).run
      _ <- Rxn.unsafe.embedUnsafe { implicit ir =>
        p2.unsafeComplete("foo")
      }.run
      _ <- assertResultF(fib1.joinWithNever, 42)
      _ <- assertResultF(fib2.joinWithNever, "foo")
    } yield ()
  }
}

trait PromiseSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("Completing a fulfilled promise should not be possible") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.complete(42).run[F], true)
      _ <- assertResultF(p.complete(42).run[F], false)
      _ <- assertResultF(p.complete(99).run[F], false)
      _ <- assertResultF(p.complete(99).run[F], false)
    } yield ()
  }

  test("Completing a fulfilled promise should not call any callbacks") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.complete(42).run[F], true)
      cnt <- F.delay { new AtomicLong(0L) }
      act = p.get.map { x =>
        cnt.incrementAndGet()
        x
      }
      res1 <- act
      res2 <- act
      b <- (p.complete(42)).run[F]
      _ <- assertF(!b)
      _ <- assertEqualsF(res1, 42)
      _ <- assertEqualsF(res2, 42)
      _ <- assertResultF(F.delay { cnt.get() }, 2L)
    } yield ()
  }

  test("Promise#get should be rerunnable") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p <- Promise[Int].run[F]
      l1 <- CountDownLatch[F](1)
      l2 <- CountDownLatch[F](1)
      g = p.get
      f1 <- (l1.release >> g).start
      f2 <- (l2.release >> g).start
      _ <- l1.await
      ok <- p.complete(42).run[F]
      _ <- l2.await
      _ <- assertF(ok)
      _ <- assertResultF(g, 42)
      _ <- assertResultF(g, 42)
      _ <- assertResultF(f1.joinWithNever, 42)
      _ <- assertResultF(f2.joinWithNever, 42)
    } yield ()
  }

  test("Promise#tryGet should work") {
    for {
      p <- Promise[Int].run[F]
      _ <- assertResultF(p.tryGet.run[F], None)
      _ <- assertResultF(p.complete(42).run[F], true)
      _ <- assertResultF(p.tryGet.run[F], Some(42))
      _ <- assertResultF(p.tryGet.run[F], Some(42))
    } yield ()
  }

  test("Invariant functor instance") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p <- Promise[Int].run[F]
      p2 = Invariant[Promise].imap(p)(_ * 2)(_ / 2)
      f <- p.get.start
      _ <- assertResultF(p2.complete(42).run[F], true)
      _ <- assertResultF(p2.complete(99).run[F], false)
      _ <- assertResultF(f.joinWithNever, 21)
    } yield ()
  }

  test("Contravariant functor instance") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p <- Promise[Int].run[F]
      pw = (p : Promise.Complete[Int])
      p2 = Contravariant[Promise.Complete].contramap[Int, Int](pw)(_ / 2)
      f <- p.get.start
      _ <- assertResultF(p2.complete(42).run[F], true)
      _ <- assertResultF(p2.complete(99).run[F], false)
      _ <- assertResultF(f.joinWithNever, 21)
    } yield ()
  }

  test("Covariant functor instance") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p <- Promise[Int].run[F]
      pr = (p : Promise.Get[Int])
      p2 = Functor[Promise.Get].map(pr)(_ * 2)
      f <- p2.get.start
      _ <- assertResultF(p.complete(21).run[F], true)
      _ <- assertResultF(f.joinWithNever, 42)
      _ <- assertResultF(p.complete(99).run, false)
    } yield ()
  }

  test("Promise#toCats") {
    for {
      _ <- assumeF(this.mcasImpl.isThreadSafe)
      p1 <- Promise[Int].run[F]
      d1 = p1.toCats
      fib1 <- d1.get.start
      _ <- assertResultF(p1.complete(42).run[F], true)
      _ <- assertResultF(fib1.joinWithNever, 42)
      p2 <- Promise[Int].run[F]
      d2 = p2.toCats
      fib2 <- p2.get.start
      _ <- assertResultF(d2.complete(21), true)
      _ <- assertResultF(fib2.joinWithNever, 21)
      p3 <- Promise[Int].run[F]
      d3 = p3.toCats
      fib3 <- d3.get.start
      _ <- assertResultF(p3.complete(44).run[F], true)
      _ <- assertResultF(d3.tryGet, Some(44))
      _ <- assertResultF(fib3.joinWithNever, 44)
    } yield ()
  }
}
