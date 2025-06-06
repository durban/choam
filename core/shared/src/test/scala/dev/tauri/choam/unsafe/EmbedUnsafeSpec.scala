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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

import core.{ Rxn, Ref }

final class EmbedUnsafeSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with EmbedUnsafeSpec[IO]

final class EmbedUnsafeSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with EmbedUnsafeSpec[zio.Task]

trait EmbedUnsafeSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("embedUnsafe") {
    for {
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      rxn = ref1.get.flatMapF { v1 =>
        ref1.set1(v1 + 1) *> Rxn.unsafe.embedUnsafe[Unit] { implicit ir =>
          assertEquals(ref1.value, 1)
          assertEquals(ref2.value, 0)
          ref1.value = 42
          ref2.value = 99
        } *> (ref1.get, ref2.get).tupled
      }
      _ <- assertResultF(rxn.run[F], (42, 99))
      _ <- assertResultF(ref1.get.run, 42)
      _ <- assertResultF(ref2.get.run, 99)
    } yield ()
  }

  test("retryNow in embedUsafe") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      res <- (ref2.update(_ + 1) *> Rxn.unsafe.embedUnsafe { implicit ir =>
        updateRef(ref)(_ + 1)
        if (ctr.incrementAndGet() < 5) {
          alwaysRetry()
        } else {
          ref.value
        }
      }).run[F]
      _ <- assertEqualsF(res, 1)
      _ <- assertResultF(ref.get.run[F], 1)
      _ <- assertResultF(ref2.get.run[F], 1)
      _ <- assertResultF(F.delay(ctr.get()), 5)
    } yield ()
  }
}
