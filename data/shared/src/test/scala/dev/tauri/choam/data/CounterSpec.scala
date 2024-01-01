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
package data

import cats.effect.IO

final class CounterSpecSimple_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with CounterSpecSimple[IO]

trait CounterSpecSimple[F[_]] extends CounterSpec[F] { this: McasImplSpec =>
  final override def mkCounter(initial: Long): F[Counter] =
    F.delay { Counter.unsafe(initial) }
}

trait CounterSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  protected def mkCounter(initial: Long): F[Counter]

  test("Counter functionality") {
    for {
      c <- mkCounter(0L)
      _ <- assertResultF(c.count.run[F], 0L)
      _ <- c.incr.run[F]
      _ <- assertResultF(c.count.run[F], 1L)
      _ <- c.add[F](42L)
      _ <- assertResultF(c.count.run[F], 43L)
      _ <- c.add[F](-43L)
      _ <- assertResultF(c.count.run[F], 0L)
      _ <- c.decr.run[F]
      _ <- assertResultF(c.count.run[F], -1L)
    } yield ()
  }

  test("Multiple ops in one Rxn") {
    for {
      c <- mkCounter(42L)
      rxn = (c.incr *> c.count) * (c.decr *> c.count)
      _ <- assertResultF(rxn.run[F], (43L, 42L))
      _ <- assertResultF(c.count.run[F], 42L)
    } yield ()
  }
}
