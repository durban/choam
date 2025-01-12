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

final class MixinSpec extends munit.CatsEffectSuite {

  test("RxnAppMixin") {
    for {
      _ <- IO(assertEquals(MainWithRxnAppMixin.globalInt.get(), 0))
      _ <- MainWithRxnAppMixin.run(List.empty)
      _ <- IO(assertEquals(MainWithRxnAppMixin.globalInt.get(), 43))
    } yield ()
  }

  test("TxnAppMixin") {
    for {
      _ <- IO(assertEquals(MainWithTxnAppMixin.globalInt.get(), 0))
      _ <- MainWithTxnAppMixin.run(List.empty)
      _ <- IO(assertEquals(MainWithTxnAppMixin.globalInt.get(), 44))
    } yield ()
  }

  test("Both (1)") {
    for {
      _ <- IO(assertEquals(MainWithBoth1.globalInt.get(), 0))
      _ <- MainWithBoth1.run(List.empty)
      _ <- IO(assertEquals(MainWithBoth1.globalInt.get(), 43 + 100))
    } yield ()
  }

  test("Both (2)") {
    for {
      _ <- IO(assertEquals(MainWithBoth2.globalInt.get(), 0))
      _ <- MainWithBoth2.run(List.empty)
      _ <- IO(assertEquals(MainWithBoth2.globalInt.get(), 44 + 101))
    } yield ()
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
