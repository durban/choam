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

import cats.effect.IO

final class ExchangerSpecJs_ThreadConfinedMCAS_IO
  extends BaseSpecIO
  with SpecThreadConfinedMCAS
  with ExchangerSpecJs[IO]

trait ExchangerSpecJs[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  final val iterations = 10

  test("Exchanger always retries on JS") {
    val tsk = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      f1 <- ex.exchange.?.apply[F]("foo").start
      f2 <- ex.dual.exchange.?.apply[F](42).start
      _ <- assertResultF(f1.joinWithNever, None)
      _ <- assertResultF(f2.joinWithNever, None)
    } yield ()
    tsk.replicateA(iterations)
  }
}
