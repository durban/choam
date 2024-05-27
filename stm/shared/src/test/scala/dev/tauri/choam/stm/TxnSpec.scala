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
package stm

import cats.effect.IO

final class TxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with TxnSpec[IO]

trait TxnSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  test("Hello World") {
    def txn(r: TRef[F, Int]): Txn[F, (Int, Int)] = for {
      v0 <- r.get
      _ <- r.set(99)
      v1 <- r.get
    } yield (v0, v1)

    for {
      r <- TRef[F, Int](42).commit
      _ <- assertResultF(txn(r).commit, (42, 99))
      _ <- assertResultF(r.get.commit, 99)
    } yield ()
  }
}
