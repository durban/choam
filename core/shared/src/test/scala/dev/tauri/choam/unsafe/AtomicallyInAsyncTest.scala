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
package unsafe

import cats.effect.IO

import core.Ref

final class AtomicallyInAsyncSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with AtomicallyInAsyncSpec[IO]

trait AtomicallyInAsyncSpec[F[_]] extends UnsafeApiSpecBase[F] { this: McasImplSpec =>

  private[this] val str: RetryStrategy =
    RetryStrategy.Default.withCede

  final override def runBlock[A](block: InRxn => A): F[A] = {
    api.atomicallyInAsync(str)(block)
  }

  final override def runRoBlock[A](block: InRoRxn => A): F[A] = {
    api.atomicallyReadOnlyInAsync(str)(block)
  }

  test("atomicallyInAsync with different strategies") {
    for {
      ref <- Ref(0).run[F]
      r1 <- api.atomicallyInAsync[F, Int](RetryStrategy.DefaultSleep) { implicit ir =>
        val ov = ref.value
        ref.value = ov + 1
        ov
      } (using F)
      _ <- assertEqualsF(r1, 0)
      _ <- assertResultF(ref.get.run, 1)
      r2 <- api.atomicallyInAsync[F, String](str) { implicit ir =>
        updateRef(ref)(_ + 1)
        null
      } (using F)
      _ <- assertEqualsF(r2, null)
      _ <- assertResultF(ref.get.run, 2)
    } yield ()
  }
}
