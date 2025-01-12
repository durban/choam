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
package stm

import cats.effect.IO

final class TRefSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with TRefSpec[IO]

trait TRefSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  protected def newTRef[A](initial: A): F[TRef[F, A]] =
    TRef[F, A](initial).commit

  test("TRef#updateAndGet") {
    for {
      ref <- newTRef(42)
      _ <- assertResultF(ref.updateAndGet(_ + 1).commit, 43)
      _ <- assertResultF(ref.get.commit, 43)
    } yield ()
  }
}
