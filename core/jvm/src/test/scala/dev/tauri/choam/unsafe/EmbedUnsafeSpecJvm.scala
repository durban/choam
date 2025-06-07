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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

import core.{ Rxn, Ref }

final class EmbedUnsafeSpecJvm_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with EmbedUnsafeSpecJvm[IO]

final class EmbedUnsafeSpecJvm_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with EmbedUnsafeSpecJvm[zio.Task]

trait EmbedUnsafeSpecJvm[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("embedUnsafe with concurrent modification") {
    for {
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      retryCounter <- F.delay(new AtomicInteger)
      fib <- (ref1.update(_ + 1) *> Rxn.unsafe.embedUnsafe[(Int, Int)] { implicit ir =>
        latch1.countDown()
        // concurrent modification to refs
        latch2.await()
        val v2 = try {
          ref2.value
        } catch {
          case ex: RetryException =>
            retryCounter.incrementAndGet()
            throw ex
        }
        (ref1.value, v2)
      }).run[F].start
      res2 <- F.delay(latch1.await()) *> (ref1.getAndUpdate(_ + 1) * ref2.getAndUpdate(_ + 1)).run[F] <* F.delay(latch2.countDown())
      _ <- assertEqualsF(res2, (0, 0))
      res1 <- fib.joinWithNever
      _ <- assertEqualsF(res1, (2, 1))
      _ <- assertResultF(F.delay(retryCounter.get()), 1)
    } yield ()
  }
}
