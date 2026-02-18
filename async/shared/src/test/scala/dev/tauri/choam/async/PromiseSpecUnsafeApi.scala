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

import core.Rxn
import unsafe.{ InRxn, UnsafeApi, alwaysRetry }

final class PromiseSpecUnsafeApi_EmbedUnsafe_DefaultMcas_IO
  extends BaseSpecTickedIO
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlock[A](block: InRxn => A): IO[A] =
    Rxn.unsafe.embedUnsafe(block(_)).run[IO]
}

final class PromiseSpecUnsafeApi_Atomically_DefaultMcas_IO
  extends BaseSpecTickedIO
  with UnsafeApiMixin[IO]
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlock[A](block: InRxn => A): IO[A] =
    F.delay { this.api.atomically(block) }
}

final class PromiseSpecUnsafeApi_AtomicallyInAsync_DefaultMcas_IO
  extends BaseSpecTickedIO
  with UnsafeApiMixin[IO]
  with SpecDefaultMcas
  with PromiseSpecUnsafeApi[IO] {

  protected final override def runBlock[A](block: InRxn => A): IO[A] =
    this.api.atomicallyInAsync[IO, A](RetryStrategy.Default.withCede)(block)
}

trait PromiseSpecUnsafeApi[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec & TestContextSpec[F] =>

  protected def runBlock[A](block: InRxn => A): F[A]

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
      _ <- runBlock { implicit ir =>
        p2.unsafeComplete("foo")
      }
      _ <- assertResultF(fib1.joinWithNever, 42)
      _ <- assertResultF(fib2.joinWithNever, "foo")
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
