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
package bench
package util

import cats.effect.IO

import io.github.timwspence.cats.stm.STM

import munit.CatsEffectSuite

final class StmQueueCSpecIO
  extends BaseSpecIO
  with StmQueueCSpec[IO]

trait StmQueueCSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] with SpecNoMcas {

  test("StmQueueC should be a correct queue") {
    for {
      s <- STM.runtime[F](STM.Make.asyncInstance(F))
      q <- {
        val qu = StmQueueCLike[STM, F](s)
        s.commit(StmQueueC.make(qu)(List.empty[Int]))
      }
      _ <- assertResultF(s.commit(q.toList), Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), None)
      _ <- s.commit(q.enqueue(1))
      _ <- assertResultF(s.commit(q.toList), 1 :: Nil)
      _ <- s.commit(q.enqueue(2))
      _ <- s.commit(q.enqueue(3))
      _ <- assertResultF(s.commit(q.toList), 1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), Some(1))
      _ <- assertResultF(s.commit(q.toList), 2 :: 3 :: Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), Some(2))
      _ <- assertResultF(s.commit(q.toList), 3 :: Nil)
      _ <- s.commit(q.enqueue(9))
      _ <- assertResultF(s.commit(q.toList), 3 :: 9 :: Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), Some(3))
      _ <- assertResultF(s.commit(q.toList), 9 :: Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), Some(9))
      _ <- assertResultF(s.commit(q.toList), Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), None)
      _ <- assertResultF(s.commit(q.toList), Nil)
      _ <- assertResultF(s.commit(q.tryDequeue), None)
    } yield ()
  }

  test("StmQueueC should have composable transactions") {
    val N = 1000
    for {
      s <- STM.runtime[F](STM.Make.asyncInstance(F))
      qu = StmQueueCLike[STM, F](s)
      qs <- {
        val tsk: F[qu.StmQueueC[Int]] = qu.stm.commit(StmQueueC.make[STM, F, Int](qu)(List.empty[Int]))
        F.both(tsk, tsk)
      }
      (q1, q2) = qs
      x1 <- F.delay(XorShift())
      x2 <- F.delay(XorShift())
      enq = { (xs: XorShift) =>
        F.replicateA(N, F.delay(xs.nextInt()).flatMap { item =>
          qu.stm.commit(q1.enqueue(item) >> q2.enqueue(item))
        })
      }
      deq = F.replicateA(N, qu.stm.commit(qu.stm.Txn.monadForTxn.product(q1.tryDequeue, q2.tryDequeue)).flatMap {
        case (None, None) =>
          F.unit // OK, empty queues
        case (Some(v1), Some(v2)) =>
          assertEqualsF(v1, v2)
        case (x, y) =>
          clue(x)
          clue(y)
          assertF(false)
      })
      fpu1 <- enq(x1).start
      fpo1 <- deq.start
      fpu2 <- enq(x2).start
      fpo2 <- deq.start
      _ <- fpu1.joinWithNever
      _ <- fpu2.joinWithNever
      _ <- fpo1.joinWithNever
      _ <- fpo2.joinWithNever
    } yield ()
  }
}
