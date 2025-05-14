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
package data

import cats.effect.IO

final class UnboundedDequeSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with UnboundedDequeSpec[IO]

final class UnboundedDequeSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with UnboundedDequeSpec[zio.Task]

trait UnboundedDequeSpec[F[_]] extends BaseSpecF[F] { this: McasImplSpec =>

  test("Basics") {
    for {
      dq <- UnboundedDeque[Int].run[F]
      _ <- assertResultF(dq.tryTakeFirst.run[F], None)
      _ <- assertResultF(dq.tryTakeLast.run[F], None)
      _ <- dq.addFirst(1).run[F]
      _ <- assertResultF(dq.tryTakeFirst.run[F], Some(1))
      _ <- assertResultF(dq.tryTakeFirst.run[F], None)
      _ <- assertResultF(dq.tryTakeLast.run[F], None)
      _ <- dq.addFirst(2).run[F]
      _ <- assertResultF(dq.tryTakeLast.run[F], Some(2))
      _ <- assertResultF(dq.tryTakeFirst.run[F], None)
      _ <- assertResultF(dq.tryTakeLast.run[F], None)
      _ <- dq.addFirst(3).run[F] // 3
      _ <- dq.addLast(4).run[F] // 3, 4
      _ <- dq.addFirst(2).run[F] // 2, 3, 4
      _ <- dq.addLast(5).run[F] // 2, 3, 4, 5
      _ <- dq.addLast(6).run[F] // 2, 3, 4, 5, 6
      _ <- assertResultF(dq.tryTakeFirst.run[F], Some(2)) // 3, 4, 5, 6
      _ <- assertResultF(dq.tryTakeFirst.run[F], Some(3)) // 4, 5, 6
      _ <- assertResultF((dq.tryTakeLast * dq.tryTakeFirst).run[F], (Some(6), Some(4))) // 5
      _ <- assertResultF(dq.tryTakeLast.run[F], Some(5)) // empty
      _ <- assertResultF(dq.tryTakeFirst.run[F], None)
      _ <- assertResultF(dq.tryTakeLast.run[F], None)
    } yield ()
  }
}
