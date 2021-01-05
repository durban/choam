/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
import cats.syntax.all._

import scala.concurrent.stm._

import munit.CatsEffectSuite

class StmQueueSpec extends CatsEffectSuite with BaseSpecA {

  test("StmQueue should be a correct queue") {
    val q = new StmQueue[Int]
    assertEquals(q.unsafeToList(), Nil)
    assertEquals(q.tryDequeue(), None)
    q.enqueue(1)
    assertEquals(q.unsafeToList(), 1 :: Nil)
    q.enqueue(2)
    q.enqueue(3)
    assertEquals(q.unsafeToList(), 1 :: 2 :: 3 :: Nil)
    assertEquals(q.tryDequeue(), Some(1))
    assertEquals(q.unsafeToList(), 2 :: 3 :: Nil)
    assertEquals(q.tryDequeue(), Some(2))
    assertEquals(q.unsafeToList(), 3 :: Nil)
    q.enqueue(9)
    assertEquals(q.unsafeToList(), 3 :: 9 :: Nil)
    assertEquals(q.tryDequeue(), Some(3))
    assertEquals(q.unsafeToList(), 9 :: Nil)
    assertEquals(q.tryDequeue(), Some(9))
    assertEquals(q.unsafeToList(), Nil)
    assertEquals(q.tryDequeue(), None)
    assertEquals(q.unsafeToList(), Nil)
    assertEquals(q.tryDequeue(), None)
  }

  test("StmQueue should not lose items") {
    val q = new StmQueue[Int]
    val N = 1000000
    def enq(xs: XorShift): Unit = {
      for (_ <- 1 to N) {
        q.enqueue(xs.nextInt())
      }
    }
    def deq(i: Int, cs: Int = 0): Int = {
      if (i > 0) {
        q.tryDequeue() match {
          case Some(item) =>
            deq(i - 1, cs ^ item)
          case None =>
            deq(i, cs)
        }
      } else {
        cs
      }
    }

    val seed1 = ThreadLocalRandom.current().nextInt()
    val seed2 = ThreadLocalRandom.current().nextInt()
    val tsk = for {
      fpu1 <- IO.blocking { enq(XorShift(seed1)) }.start
      fpu2 <- IO.blocking { enq(XorShift(seed2)) }.start
      fpo1 <- IO.blocking { deq(N) }.start
      fpo2 <- IO.blocking { deq(N) }.start
      _ <- fpu1.joinWithNever
      _ <- fpu2.joinWithNever
      cs1 <- fpo1.joinWithNever
      cs2 <- fpo2.joinWithNever
    } yield cs1 ^ cs2

    val cs = tsk.unsafeRunSync()
    val xs1 = XorShift(seed1)
    val expCs1 = (1 to N).foldLeft(0) { (cs, _) =>
      cs ^ xs1.nextInt()
    }
    val xs2 = XorShift(seed2)
    val expCs2 = (1 to N).foldLeft(0) { (cs, _) =>
      cs ^ xs2.nextInt()
    }
    assertEquals(cs, (expCs1 ^ expCs2))
    assertEquals(q.unsafeToList(), Nil)
  }

  test("StmQueue should have composable transactions") {
    val q1 = new StmQueue[Int]
    val q2 = new StmQueue[Int]
    val N = 1000000
    def enq(xs: XorShift): Unit = {
      for (_ <- 1 to N) {
        val item = xs.nextInt()
        atomic { implicit txn =>
          q1.enqueue(item)
          q2.enqueue(item)
        }
      }
    }
    def deq(): Unit = {
      for (_ <- 1 to N) {
        atomic { implicit txn =>
          val i1 = q1.tryDequeue()
          val i2 = q2.tryDequeue()
          (i1, i2) match {
            case (Some(v1), Some(v2)) =>
              if (v1 =!= v2) fail(s"Dequeued different values: ${v1} and ${v2}")
            case (None, None) =>
              // OK, empty queues
            case _ =>
              fail(s"Dequeued different items: ${i1} and ${i2}")
          }
        }
      }
    }
    val tsk = for {
      fpu1 <- IO.blocking { enq(XorShift()) }.start
      fpo1 <- IO.blocking { deq() }.start
      fpu2 <- IO.blocking { enq(XorShift()) }.start
      fpo2 <- IO.blocking { deq() }.start
      _ <- fpu1.join
      _ <- fpu2.join
      _ <- fpo1.join
      _ <- fpo2.join
    } yield ()

    tsk.unsafeRunSync()
  }
}
