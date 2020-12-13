/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.concurrent.stm._

class StmQueueSpec extends BaseSpec {

  "StmQueue" should "be a correct queue" in {
    val q = new StmQueue[Int]
    q.unsafeToList() should === (Nil)
    q.tryDequeue() should === (None)
    q.enqueue(1)
    q.unsafeToList() should === (1 :: Nil)
    q.enqueue(2)
    q.enqueue(3)
    q.unsafeToList() should === (1 :: 2 :: 3 :: Nil)
    q.tryDequeue() should === (Some(1))
    q.unsafeToList() should === (2 :: 3 :: Nil)
    q.tryDequeue() should === (Some(2))
    q.unsafeToList() should === (3 :: Nil)
    q.enqueue(9)
    q.unsafeToList() should === (3 :: 9 :: Nil)
    q.tryDequeue() should === (Some(3))
    q.unsafeToList() should === (9 :: Nil)
    q.tryDequeue() should === (Some(9))
    q.unsafeToList() should === (Nil)
    q.tryDequeue() should === (None)
    q.unsafeToList() should === (Nil)
    q.tryDequeue() should === (None)
  }

  it should "not lose items" in {
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
      fpu1 <- IO { enq(XorShift(seed1)) }.start
      fpu2 <- IO { enq(XorShift(seed2)) }.start
      fpo1 <- IO { deq(N) }.start
      fpo2 <- IO { deq(N) }.start
      _ <- fpu1.join
      _ <- fpu2.join
      cs1 <- fpo1.join
      cs2 <- fpo2.join
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
    cs should === (expCs1 ^ expCs2)
    q.unsafeToList() should === (Nil)
  }

  it should "have composable transactions" in {
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
              if (v1 !== v2) fail(s"Dequeued different values: ${v1} and ${v2}")
            case (None, None) =>
              // OK, empty queues
            case _ =>
              fail(s"Dequeued different items: ${i1} and ${i2}")
          }
        }
      }
    }
    val tsk = for {
      fpu1 <- IO { enq(XorShift()) }.start
      fpo1 <- IO { deq() }.start
      fpu2 <- IO { enq(XorShift()) }.start
      fpo2 <- IO { deq() }.start
      _ <- fpu1.join
      _ <- fpu2.join
      _ <- fpo1.join
      _ <- fpo2.join
    } yield ()

    tsk.unsafeRunSync()
  }
}
