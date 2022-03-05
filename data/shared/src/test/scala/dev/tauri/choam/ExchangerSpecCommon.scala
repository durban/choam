/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.IO

final class ExchangerSpecCommon_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with ExchangerSpecCommon[IO]

trait ExchangerSpecCommon[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  final val iterations = 10

  test("A single party never succeeds with an exchange") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      _ <- assertResultF(ex.exchange.?.apply[F]("foo"), None)
    } yield ()
    tsk.replicateA(iterations)
  }

  test("Different runs produce distinct exchanger objects") {
    for {
      _ <- F.unit
      alloc = Rxn.unsafe.exchanger[String, Int]
      tsk = alloc.run[F]
      f1 <- tsk.start // these may run on
      f2 <- tsk.start // different threads
      ex1 <- f1.joinWithNever
      ex2 <- f2.joinWithNever
      _ <- assertF(ex1 ne ex2)
    } yield ()
  }

  test("The dual is always the same object") {
    for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- F.delay(ex.dual).start // these may run on
      f2 <- F.delay(ex.dual).start // different threads
      d1 <- f1.joinWithNever
      d2 <- f2.joinWithNever
      _ <- assertSameInstanceF(d1, d2)
    } yield ()
  }

  test("The dual of an exchanger's dual is itself (object identity)") {
    for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      dex = ex.dual
      _ <- assertSameInstanceF(dex.dual, ex)
    } yield ()
  }

  test("The dual must have the same key") {
    for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      _ <- assertSameInstanceF(ex.dual.key, ex.key)
    } yield ()
  }
}
