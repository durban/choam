/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Rxn, Ref }

trait EmbedUnsafeSpecPlatform[F[_]] { this: EmbedUnsafeSpec[F] =>

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

  test("embedUnsafe race") {
    val N = 8192
    for {
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      rxn1 = ref1.update(_ + 1) *> Rxn.unsafe.embedUnsafe[Unit] { implicit ir =>
        val ov = ref2.value
        assertEquals(ref1.value, ov + 1)
        ref2.value = ov + 1
      }
      rxn2 = ref2.update(_ + 1) *> Rxn.unsafe.embedUnsafe[Unit] { implicit ir =>
        val ov = ref1.value
        assertEquals(ref2.value, ov + 1)
        ref1.value = ov + 1
      }
      _ <- F.both(
        F.cede *> rxn1.run[F].replicateA_(N),
        F.cede *> rxn2.run[F].replicateA_(N),
      )
      _ <- assertResultF(ref1.get.run[F], 2 * N)
      _ <- assertResultF(ref2.get.run[F], 2 * N)
    } yield ()
  }
}
