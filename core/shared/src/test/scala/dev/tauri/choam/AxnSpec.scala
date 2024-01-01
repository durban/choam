/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

final class AxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with AxnSpec[IO]

trait AxnSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("Axn.unsafe.delay/suspend") {
    val ctr = new AtomicInteger
    val rxn1 = Axn.unsafe.delay { ctr.incrementAndGet() }
    val rxn2 = Axn.unsafe.suspend {
      val x = ctr.incrementAndGet()
      Rxn.pure(x)
    }
    for {
      _ <- assertResultF(rxn1.run[F], 1)
      _ <- assertResultF(rxn1.run[F], 2)
      _ <- assertResultF(rxn2.run[F], 3)
      _ <- assertResultF(rxn2.run[F], 4)
    } yield ()
  }

  test("Axn.unsafe.context/suspendContext") {
    val rxn1 = Axn.unsafe.context { ctx =>
      ctx.random.nextLong(42L)
    }
    val rxn2 = Axn.unsafe.suspendContext { ctx =>
      val x = ctx.random.nextLong(42L)
      Rxn.pure(x)
    }
    for {
      x1 <- rxn1.run[F]
      _ <- assertF(x1 < 42L)
      x2 <- rxn1.run[F]
      _ <- assertF(x2 < 42L)
      x3 <- rxn2.run[F]
      _ <- assertF(x3 < 42L)
      x4 <- rxn2.run[F]
      _ <- assertF(x4 < 42L)
    } yield ()
  }
}
