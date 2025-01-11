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
package ce

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.{ IO, IOApp }

import stm.TRef

// TODO: fix running these tests on JS
final class MixinSpec extends munit.FunSuite {

  test("RxnAppMixin") {
    assertEquals(MainWithRxnAppMixin.globalInt.get(), 0)
    MainWithRxnAppMixin.main(new Array[String](0))
    assertEquals(MainWithRxnAppMixin.globalInt.get(), 43)
  }

  test("TxnAppMixin") {
    assertEquals(MainWithTxnAppMixin.globalInt.get(), 0)
    MainWithTxnAppMixin.main(new Array[String](0))
    assertEquals(MainWithTxnAppMixin.globalInt.get(), 44)
  }

  test("Both (1)") {
    assertEquals(MainWithBoth1.globalInt.get(), 0)
    MainWithBoth1.main(new Array[String](0))
    assertEquals(MainWithBoth1.globalInt.get(), 43 + 100)
  }

  test("Both (2)") {
    assertEquals(MainWithBoth2.globalInt.get(), 0)
    MainWithBoth2.main(new Array[String](0))
    assertEquals(MainWithBoth2.globalInt.get(), 44 + 101)
  }
}

object MainWithRxnAppMixin extends IOApp.Simple with RxnAppMixin {

  val globalInt =
    new AtomicInteger

  def run: IO[Unit] = for {
    ref <- Ref[Int](42).run
    x <- ref.updateAndGet(_ + 1).run
    _ <- IO { globalInt.set(x) }
  } yield ()
}

object MainWithTxnAppMixin extends IOApp.Simple with TxnAppMixin {

  val globalInt =
    new AtomicInteger

  def run: IO[Unit] = for {
    tref <- TRef[IO, Int](42).commit
    _ <- tref.update(_ + 2).commit
    x <- tref.get.commit
    _ <- IO { globalInt.set(x) }
  } yield ()
}

object MainWithBoth1 extends IOApp.Simple with RxnAppMixin with TxnAppMixin {

  val globalInt =
    new AtomicInteger

  def run: IO[Unit] = for {
    ref <- Ref[Int](42).run
    tref <- TRef[IO, Int](99).commit
    x <- ref.updateAndGet(_ + 1).run
    _ <- tref.update(_ + 1).commit
    y <- tref.get.commit
    _ <- IO { globalInt.set(x + y) }
  } yield ()
}

object MainWithBoth2 extends IOApp.Simple with TxnAppMixin with RxnAppMixin {

  val globalInt =
    new AtomicInteger

  def run: IO[Unit] = for {
    ref <- Ref[Int](42).run
    tref <- TRef[IO, Int](99).commit
    x <- ref.updateAndGet(_ + 2).run
    _ <- tref.update(_ + 2).commit
    y <- tref.get.commit
    _ <- IO { globalInt.set(x + y) }
  } yield ()
}
