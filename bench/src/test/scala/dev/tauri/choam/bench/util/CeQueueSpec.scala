/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package util

import java.util.concurrent.ThreadLocalRandom

import cats.effect.IO

import munit.CatsEffectSuite

final class CeQueueSpecIO
  extends BaseSpecIO
  with CeQueueSpec[IO]

trait CeQueueSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] with SpecNoKCAS {

  test("CeQueue should be a correct queue") {
    for {
      q <- CeQueue[F, Int]
      _ <- assertResultF(q.toList, Nil)
      _ <- assertResultF(q.tryDequeue, None)
      _ <- q.enqueue(1)
      _ <- assertResultF(q.toList, 1 :: Nil)
      _ <- q.enqueue(2)
      _ <- q.enqueue(3)
      _ <- assertResultF(q.toList, 1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(q.tryDequeue, Some(1))
      _ <- assertResultF(q.toList, 2 :: 3 :: Nil)
      _ <- assertResultF(q.tryDequeue, Some(2))
      _ <- assertResultF(q.toList, 3 :: Nil)
      _ <- q.enqueue(9)
      _ <- assertResultF(q.toList, 3 :: 9 :: Nil)
      _ <- assertResultF(q.tryDequeue, Some(3))
      _ <- assertResultF(q.toList, 9 :: Nil)
      _ <- assertResultF(q.tryDequeue, Some(9))
      _ <- assertResultF(q.toList, Nil)
      _ <- assertResultF(q.tryDequeue, None)
      _ <- assertResultF(q.toList, Nil)
      _ <- assertResultF(q.tryDequeue, None)
    } yield ()
  }

  test("CeQueue should not lose items") {
    val N = 100000
    def enq(q: CeQueue[F, Int], xs: XorShift): F[Unit] = {
      F.replicateA(N, F.delay(xs.nextInt()).flatMap(q.enqueue)).void
    }
    def deq(q: CeQueue[F, Int], i: Int, cs: Int = 0): F[Int] = {
      if (i > 0) {
        q.tryDequeue.flatMap {
          case Some(item) =>
            deq(q, i - 1, cs ^ item)
          case None =>
            deq(q, i, cs)
        }
      } else {
        F.pure(cs)
      }
    }

    for {
      q <- CeQueue[F, Int]
      seed1 <- F.delay { ThreadLocalRandom.current().nextInt() }
      seed2 <- F.delay { ThreadLocalRandom.current().nextInt() }
      x1 <- F.delay { XorShift(seed1) }
      x2 <- F.delay { XorShift(seed2) }
      fpu1 <- enq(q, x1).start
      fpu2 <- enq(q, x2).start
      fpo1 <- deq(q, N).start
      fpo2 <- deq(q, N).start
      _ <- fpu1.joinWithNever
      _ <- fpu2.joinWithNever
      cs1 <- fpo1.joinWithNever
      cs2 <- fpo2.joinWithNever
      cs = cs1 ^ cs2
      xs1 <- F.delay { XorShift(seed1) }
      xs2 <- F.delay { XorShift(seed2) }
      expCs1 <- (1 to N).toList.foldLeftM(0) { (cs, _) =>
        F.delay(xs1.nextInt()).map { ni =>
          cs ^ ni
        }
      }
      expCs2 <- (1 to N).toList.foldLeftM(0) { (cs, _) =>
        F.delay(xs2.nextInt()).map { ni =>
          cs ^ ni
        }
      }
      _ <- assertEqualsF(cs, (expCs1 ^ expCs2))
      _ <- assertResultF(q.toList, Nil)
    } yield ()
  }
}
