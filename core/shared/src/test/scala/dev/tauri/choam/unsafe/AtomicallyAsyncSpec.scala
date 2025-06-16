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

import core.{ Ref, RetryStrategy }

final class AtomicallyAsyncSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with AtomicallyAsyncSpec[IO]

final class AtomicallyAsyncSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with AtomicallyAsyncSpec[zio.Task]

trait AtomicallyAsyncSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  private[this] val api: UnsafeApi =
    UnsafeApi(this.runtime)

  import api._

  private val cede =
    RetryStrategy.Default.withCede(true)

  test("Basics") {
    for {
      ref <- Ref(0).run[F]
      r1 <- atomicallyInAsync[F, Int](RetryStrategy.DefaultSleep) { implicit ir =>
        val ov = ref.value
        ref.value = ov + 1
        ov
      } (using F)
      _ <- assertEqualsF(r1, 0)
      _ <- assertResultF(ref.get.run, 1)
      r2 <- atomicallyInAsync[F, String](cede) { implicit ir =>
        updateRef(ref)(_ + 1)
        null
      } (using F)
      _ <- assertEqualsF(r2, null)
      _ <- assertResultF(ref.get.run, 2)
    } yield ()
  }

  test("Forced retries") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- atomicallyInAsync(RetryStrategy.Default)(newRef(42)(using _))(using F)
      tsk = atomicallyInAsync(cede) { implicit ir =>
        updateRef(ref)(_ + 1)
        if (ctr.incrementAndGet() < 5) {
          alwaysRetry()
        }
        ref.value
      } (using F)
      _ <- assertResultF(tsk, 43)
      _ <- assertResultF(F.delay(atomically(ref.value(using _))), 43)
      _ <- assertResultF(F.delay(ctr.get()), 5)
      _ <- F.delay(ctr.set(0))
      _ <- assertResultF(tsk, 44)
      _ <- assertResultF(F.delay(atomically(ref.value(using _))), 44)
      _ <- assertResultF(F.delay(ctr.get()), 5)
    } yield ()
  }
}
