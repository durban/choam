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

import core.Ref

trait CommonImperativeApiSpecPlatform[F[_]] { this: CommonImperativeApiSpec[F] =>

  test("Retries") {

    def txn1(r1: Ref[Int], r2: Ref[Int], latch1: CountDownLatch, latch2: CountDownLatch)(implicit ir: InRxn): Unit = {
      val v1 = readRef(r1)
      latch1.countDown()
      latch2.await()
      val v2 = readRef(r2)
      assertEquals(v1, v2)
    }

    def txn2(r1: Ref[Int], r2: Ref[Int])(implicit ir: InRxn): Unit = {
      val v1 = readRef(r1)
      val nv = v1 + 1
      writeRef(r1, nv)
      writeRef(r2, nv)
    }

    for {
      r1 <- runBlock(newRef(0)(using _))
      r2 <- runBlock(newRef(0)(using _))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      _ <- F.both(
        runBlock(txn1(r1, r2, latch1, latch2)(using _)),
        F.delay(latch1.await()) *> runBlock(txn2(r1, r2)(using _)) *> F.delay(latch2.countDown()),
      )
    } yield ()
  }

  test("Ticket#validate") {
    for {
      ref1 <- runBlock(newRef(0)(using _))
      ref2 <- runBlock(newRef(0)(using _))
      tries <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      tsk1 = {
        runBlock { implicit ir =>
          tries.getAndIncrement()
          val ticket = ticketRead(ref1)
          latch1.countDown()
          // refs change
          latch2.await()
          updateRef(ref2)(_ + 1)
          val v2 = ref2.value
          if (ticket.value == 0) {
            ticket.validate()
          }
          (ticket.value, v2)
        }
      }
      tsk2 = {
        F.delay(latch1.await()) *> runBlock { implicit ir =>
          updateRef(ref1)(_ + 1)
          updateRef(ref2)(_ + 1)
        } *> F.delay(latch2.countDown()).as((42, 42))
      }
      _ <- F.both(tsk1, tsk2).flatMap {
        case (res1, res2) =>
          F.delay {
            assertEquals(res1, (1, 2))
            assertEquals(res2, (42, 42))
            assertEquals(tries.get(), 2)
          }
      }
    } yield ()
  }
}
