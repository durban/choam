/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import zio.{ IO, ZIO }
import zio.stm.STM

class StmQueueZSpec extends BaseSpec {

  def assertF(cond: Boolean): IO[Throwable, Unit] =
    ZIO.attempt { assert(cond) }

  def assertEqualsF[A](x: A, y: A): IO[Throwable, Unit] =
    ZIO.attempt { assertEquals(x, y) }

  def assertResultF[A](tsk: IO[Throwable, A], expected: A): IO[Throwable, Unit] = {
    tsk.flatMap { a =>
      ZIO.attempt { assertEquals(a, expected) }
    }
  }

  test("StmQueueZ should be a correct queue") {
    val tsk = for {
      q <- StmQueueZ[Int](Nil)
      _ <- assertResultF(STM.atomically(q.toList), Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), None)
      _ <- STM.atomically(q.enqueue(1))
      _ <- assertResultF(STM.atomically(q.toList), 1 :: Nil)
      _ <- STM.atomically(q.enqueue(2))
      _ <- STM.atomically(q.enqueue(3))
      _ <- assertResultF(STM.atomically(q.toList), 1 :: 2 :: 3 :: Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), Some(1))
      _ <- assertResultF(STM.atomically(q.toList), 2 :: 3 :: Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), Some(2))
      _ <- assertResultF(STM.atomically(q.toList), 3 :: Nil)
      _ <- STM.atomically(q.enqueue(9))
      _ <- assertResultF(STM.atomically(q.toList), 3 :: 9 :: Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), Some(3))
      _ <- assertResultF(STM.atomically(q.toList), 9 :: Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), Some(9))
      _ <- assertResultF(STM.atomically(q.toList), Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), None)
      _ <- assertResultF(STM.atomically(q.toList), Nil)
      _ <- assertResultF(STM.atomically(q.tryDequeue), None)
    } yield ()

    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(tsk).foldExit(
        failed = err => assert(false, s"task failed: ${err}"),
        completed = _ => ()
      )
    }
  }

  test("StmQueueZ should have composable transactions") {
    val N = 1000
    val tsk = for {
      q1 <- StmQueueZ[Int](Nil)
      q2 <- StmQueueZ[Int](Nil)
      x1 <- ZIO.attempt(XorShift())
      x2 <- ZIO.attempt(XorShift())
      enq = { (xs: XorShift) =>
        ZIO.attempt(xs.nextInt()).flatMap { item =>
          STM.atomically(q1.enqueue(item).flatMap { _ => q2.enqueue(item) })
        }.repeatN(N)
      }
      deq = STM.atomically(q1.tryDequeue.flatMap { r1 => q2.tryDequeue.map((r1, _)) }).flatMap {
        case (None, None) =>
          ZIO.unit // OK, empty queues
        case (Some(v1), Some(v2)) =>
          assertEqualsF(v1, v2)
        case (x, y) =>
          clue(x)
          clue(y)
          assertF(false)
      }.repeatN(N)
      fpu1 <- enq(x1).fork
      fpo1 <- deq.fork
      fpu2 <- enq(x2).fork
      fpo2 <- deq.fork
      _ <- fpu1.join
      _ <- fpu2.join
      _ <- fpo1.join
      _ <- fpo2.join
    } yield ()

    zio.Unsafe.unsafe { implicit u =>
      zio.Runtime.default.unsafe.run(tsk).foldExit(
        failed = err => assert(false, s"task failed: ${err}"),
        completed = _ => ()
      )
    }
  }
}
