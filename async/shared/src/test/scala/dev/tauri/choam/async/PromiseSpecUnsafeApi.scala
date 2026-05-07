/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import cats.effect.IO

import core.{ Rxn, Ref }
import unsafe.{ InRxn, UnsafeApi, alwaysRetry, RefSyntax, embedRxn, getAndSetRef }

final class PromiseSpecUnsafeApi_EmbedUnsafe_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlockWithAlts[A](block: InRxn => A, alts: Rxn[A]*): IO[A] =
    Rxn.unsafe.embedUnsafeWithAlts(block(_), alts: _*).run[IO]
}

final class PromiseSpecUnsafeApi_Atomically_DefaultMcas_IO
  extends BaseSpecTickedIO
  with UnsafeApiMixin[IO]
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlockWithAlts[A](block: InRxn => A, alts: Rxn[A]*): IO[A] =
    F.delay { this.api.atomicallyWithAlts(RetryStrategy.Default)(block, alts: _*) }
}

final class PromiseSpecUnsafeApi_AtomicallyInAsync_DefaultMcas_IO
  extends BaseSpecTickedIO
  with UnsafeApiMixin[IO]
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlockWithAlts[A](block: InRxn => A, alts: Rxn[A]*): IO[A] =
    this.api.atomicallyInAsyncWithAlts[IO, A](RetryStrategy.Default.withCede)(block, alts: _*)
}

trait PromiseSpecUnsafeApi[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected def runBlockWithAlts[A](block: InRxn => A, alts: Rxn[A]*): F[A]

  protected def runBlock[A](block: InRxn => A): F[A] =
    runBlockWithAlts(block)

  test("Promise#unsafeComplete") {
    for {
      // no subscribers:
      p1 <- Promise[Int].run[F]
      _ <- assertResultF(runBlock { implicit ir =>
        p1.unsafeComplete(42)
      }, true)
      _ <- assertResultF(p1.tryGet.run, Some(42))
      _ <- assertResultF(runBlock { implicit ir =>
        p1.unsafeComplete(99)
      }, false)
      _ <- assertResultF(p1.tryGet.run, Some(42))
      // has subscribers:
      p2 <- Promise[Int].run[F]
      fib1 <- p2.get[F].start
      _ <- this.tickAll
      fib2 <- p2.get[F].start
      _ <- this.tickAll
      _ <- assertResultF(runBlock { implicit ir =>
        p2.unsafeComplete(42)
      }, true)
      _ <- assertResultF(runBlock { implicit ir =>
        p2.unsafeComplete(99)
      }, false)
      _ <- assertResultF(fib1.joinWithNever, 42)
      _ <- assertResultF(fib2.joinWithNever, 42)
    } yield ()
  }

  test("Promise#unsafeComplete then rollback") {
    for {
      p <- runBlock(Promise.unsafeNew[String]()(using _))
      d <- F.deferred[Unit]
      fib <- p.get.guarantee(d.complete(()).void).start
      _ <- this.tickAll
      r <- runBlock { implicit ir =>
        if (ThreadLocalRandom.current().nextBoolean()) {
          p.unsafeComplete("foo")
          alwaysRetry()
        } else {
          42
        }
      }
      _ <- assertEqualsF(r, 42)
      _ <- this.tickAll
      _ <- assertResultF(d.tryGet, None)
      _ <- fib.cancel
      _ <- assertResultF(fib.join.map(_.isCanceled), true)
    } yield ()
  }

  test("Promise.unsafeNew") {
    for {
      pp <- runBlock { implicit ir =>
        (Promise.unsafeNew[Int](), Promise.unsafeNew[String](AllocationStrategy.Padded))
      }
      (p1, p2) = pp
      fib1 <- p1.get.start
      fib2 <- p2.get.start
      _ <- this.tickAll
      _ <- p1.complete(42).run
      ok <- runBlock { implicit ir =>
        p2.unsafeComplete("foo")
      }
      _ <- assertF(ok)
      _ <- assertResultF(fib1.joinWithNever, 42)
      _ <- assertResultF(fib2.joinWithNever, "foo")
    } yield ()
  }

  test("After embedRxn") {
    for {
      r <- Ref[Int](0).run
      p <- Promise[Int].run
      f1 <- F.deferred[Unit]
      f2 <- F.deferred[Unit]
      fib1 <- p.get.guarantee(f1.complete(()).void).start
      fib2 <- p.get.guarantee(f2.complete(()).void).start
      _ <- this.tickAll
      res1 <- runBlockWithAlts(
        { implicit u =>
          r.value = 42
          assert(embedRxn(p.complete(42)))
          alwaysRetry() : Int
        },
        Rxn.pure(99)
      )
      _ <- assertEqualsF(res1, 99)
      _ <- this.tickAll
      _ <- assertResultF(f1.tryGet, None)
      _ <- assertResultF(f2.tryGet, None)
      _ <- assertResultF(r.get.run, 0)
      res2 <- runBlock { implicit u =>
        r.value = 42
        assert(p.unsafeComplete(43))
        assertEquals(getAndSetRef(r, 99), 42)
        56
      }
      _ <- assertEqualsF(res2, 56)
      _ <- this.tickAll
      _ <- assertResultF(f1.tryGet, Some(()))
      _ <- assertResultF(f2.tryGet, Some(()))
      _ <- assertResultF(r.get.run, 99)
      _ <- fib1.joinWithNever
      _ <- fib2.joinWithNever
    } yield ()
  }

  test("Within embedRxn") {
    for {
      r <- Ref[Int](0).run
      p <- Promise[Int].run
      f1 <- F.deferred[Unit]
      f2 <- F.deferred[Unit]
      fib1 <- p.get.guarantee(f1.complete(()).void).start
      fib2 <- p.get.guarantee(f2.complete(()).void).start
      _ <- this.tickAll
      res1 <- runBlockWithAlts(
        { implicit u =>
          r.value = 42
          assert(embedRxn(p.complete(42)))
          alwaysRetry() : Int
        },
        Rxn.pure(99)
      )
      _ <- assertEqualsF(res1, 99)
      _ <- this.tickAll
      _ <- assertResultF(f1.tryGet, None)
      _ <- assertResultF(f2.tryGet, None)
      _ <- assertResultF(r.get.run, 0)
      res2 <- runBlock { implicit u =>
        r.value = 42
        assert(embedRxn(p.complete(43)))
        56
      }
      _ <- assertEqualsF(res2, 56)
      _ <- this.tickAll
      _ <- assertResultF(f1.tryGet, Some(()))
      _ <- assertResultF(f2.tryGet, Some(()))
      _ <- assertResultF(r.get.run, 42)
      _ <- fib1.joinWithNever
      _ <- fib2.joinWithNever
    } yield ()
  }
}

sealed trait UnsafeApiMixin[F[_]] extends munit.FunSuite { this: McasImplSpec & BaseSpecF[F] =>

  private[this] var _api: UnsafeApi =
    null

  protected[this] def api: UnsafeApi = {
    this._api match {
      case null => fail("this._api not initialized")
      case api => api
    }
  }

  final override def beforeAll(): Unit = {
    super.beforeAll()
    this._api = UnsafeApi(this.runtime)
  }
}
