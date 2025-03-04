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
package zi

import java.util.concurrent.atomic.AtomicInteger

import zio.{ RIO, Task, ZIO, ZIOAppDefault, ZLayer }

import core.ChoamRuntime
import stm.TRef

import munit.{ FunSuite, TestOptions, Location }

final class MixinSpec extends FunSuite {

  private[this] val runtime =
    zio.Runtime.default

  private[this] def _test[A](n: TestOptions)(body: => Task[A])(implicit loc: Location): Unit = {
    super.test(n) {
      val tsk: Task[A] = body
      this.runtime.unsafe.runToFuture(tsk)(implicitly, zio.Unsafe)
    } (loc)
  }

  _test("RxnAppMixin") {
    for {
      _ <- ZIO.attempt(assertEquals(MainWithRxnAppMixin.globalInt.get(), 0))
      _ <- MainWithRxnAppMixin.run
      _ <- ZIO.attempt(assertEquals(MainWithRxnAppMixin.globalInt.get(), 43))
      _ <- ZIO.attempt(assertNotEquals(MainWithRxnAppMixin.rt, null))
    } yield ()
  }

  _test("TxnAppMixin") {
    for {
      _ <- ZIO.attempt(assertEquals(MainWithTxnAppMixin.globalInt.get(), 0))
      _ <- MainWithTxnAppMixin.run
      _ <- ZIO.attempt(assertEquals(MainWithTxnAppMixin.globalInt.get(), 44))
      _ <- ZIO.attempt(assertNotEquals(MainWithTxnAppMixin.rt, null))
    } yield ()
  }

  _test("Both (1)") {
    for {
      _ <- ZIO.attempt(assertEquals(MainWithBoth1.globalInt.get(), 0))
      _ <- MainWithBoth1.run
      _ <- ZIO.attempt(assertEquals(MainWithBoth1.globalInt.get(), 43 + 100))
      _ <- ZIO.attempt(assertNotEquals(MainWithBoth1.rt, null))
    } yield ()
  }

  _test("Both (2)") {
    for {
      _ <- ZIO.attempt(assertEquals(MainWithBoth2.globalInt.get(), 0))
      _ <- MainWithBoth2.run
      _ <- ZIO.attempt(assertEquals(MainWithBoth2.globalInt.get(), 44 + 101))
      _ <- ZIO.attempt(assertNotEquals(MainWithBoth2.rt, null))
    } yield ()
  }
}

object MainWithRxnAppMixin extends ZIOAppDefault with RxnAppMixin {

  val globalInt =
    new AtomicInteger

  val rt: ChoamRuntime =
    this.choamRuntime

  def run: Task[Unit] = for {
    ref <- Ref[Int](42).run
    x <- subtask(ref).provide(ZLayer(ZIO.attempt("foo")))
    _ <- ZIO.attempt { globalInt.set(x) }
  } yield ()

  private def subtask(ref: Ref[Int]): RIO[String, Int] = {
    ZIO.environment[String].flatMap { _ =>
      ref.updateAndGet(_ + 1).run
    }
  }
}

object MainWithTxnAppMixin extends ZIOAppDefault with TxnAppMixin {

  val globalInt =
    new AtomicInteger

  val rt: ChoamRuntime =
    this.choamRuntime

  def run: Task[Unit] = for {
    tref <- TRef[Int](42).commit
    _ <- tref.update(_ + 2).commit
    x <- tref.get.commit
    _ <- ZIO.attempt { globalInt.set(x) }
  } yield ()
}

object MainWithBoth1 extends ZIOAppDefault with RxnAppMixin with TxnAppMixin {

  val globalInt =
    new AtomicInteger

  val rt: ChoamRuntime =
    this.choamRuntime

  def run: Task[Unit] = for {
    ref <- Ref[Int](42).run
    tref <- TRef[Int](99).commit
    x <- ref.updateAndGet(_ + 1).run
    _ <- tref.update(_ + 1).commit
    y <- tref.get.commit
    _ <- ZIO.attempt { globalInt.set(x + y) }
  } yield ()
}

object MainWithBoth2 extends ZIOAppDefault with TxnAppMixin with RxnAppMixin {

  val globalInt =
    new AtomicInteger

  val rt: ChoamRuntime =
    this.choamRuntime

  def run: Task[Unit] = for {
    ref <- Ref[Int](42).run
    tref <- TRef[Int](99).commit
    x <- ref.updateAndGet(_ + 2).run
    _ <- tref.update(_ + 2).commit
    y <- tref.get.commit
    _ <- ZIO.attempt { globalInt.set(x + y) }
  } yield ()
}
