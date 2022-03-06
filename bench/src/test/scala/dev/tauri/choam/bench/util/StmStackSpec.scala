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

import scala.concurrent.stm._

import cats.effect.IO
import cats.syntax.all._

import munit.CatsEffectSuite

class StmStackSpec extends CatsEffectSuite with BaseSpecA {

  test("StmStack should be a correct stack") {
    val s = new StmStack[Int]
    assertEquals(s.unsafeToList(), Nil)
    assertEquals(s.tryPop(), None)
    assertEquals(s.unsafeToList(), Nil)
    s.push(1)
    assertEquals(s.unsafeToList(), 1 :: Nil)
    s.push(2)
    s.push(3)
    assertEquals(s.unsafeToList(), 3 :: 2 :: 1 :: Nil)
    assertEquals(s.tryPop(), Some(3))
    assertEquals(s.unsafeToList(), 2 :: 1 :: Nil)
    assertEquals(s.tryPop(), Some(2))
    assertEquals(s.tryPop(), Some(1))
    assertEquals(s.tryPop(), None)
    assertEquals(s.unsafeToList(), Nil)
  }

  test("StmStack should not lose items") {
    val s = new StmStack[Int]
    val B = 1024
    val R = 32
    val N = R * B
    val seed1 = ThreadLocalRandom.current().nextInt()
    val seed2 = ThreadLocalRandom.current().nextInt()
    def push(xs: XorShift): IO[Unit] = {
      val one = IO.blocking {
        for (_ <- 1 to B) { s.push(xs.nextInt()) }
      }
      (IO.cede *> one).replicateA_(R)
    }
    @tailrec
    def pop1(i: Int, cs: Int): Int = {
      if (i > 0) {
        s.tryPop() match {
          case Some(item) =>
            pop1(i - 1, cs ^ item)
          case None =>
            pop1(i, cs)
        }
      } else {
        cs
      }
    }
    def pop(cs: Int = 0): IO[Int] = {
      (IO.cede *> IO.blocking { pop1(B, cs = cs) }).replicateA(R).map(_.reduce(_ ^ _))
    }

    val tsk = for {
      fpu1 <- push(XorShift(seed1)).start
      fpu2 <- push(XorShift(seed2)).start
      fpo1 <- pop().start
      fpo2 <- pop().start
      _ <- fpu1.joinWithNever
      _ <- fpu2.joinWithNever
      cs1 <- fpo1.joinWithNever
      cs2 <- fpo2.joinWithNever
    } yield cs1 ^ cs2

    for {
      cs <- tsk
      xs1 <- IO { XorShift(seed1) }
      expCs1 <- IO {
        (1 to N).foldLeft(0) { (cs, _) => cs ^ xs1.nextInt() }
      }
      xs2 <- IO { XorShift(seed2) }
      expCs2 <- IO {
        (1 to N).foldLeft(0) { (cs, _) => cs ^ xs2.nextInt() }
      }
      _ <- IO { assertEquals(cs, (expCs1 ^ expCs2)) }
      _ <- IO { assertEquals(s.unsafeToList(), Nil) }
    } yield ()
  }

  test("StmStack should have composable transactions") {
    val s1 = new StmStack[Int]
    val s2 = new StmStack[Int]
    val B = 1024
    val R = 32
    def push(xs: XorShift): IO[Unit] = {
      val one = IO.blocking {
        for (_ <- 1 to B) {
          val item = xs.nextInt()
          atomic { implicit txn =>
            s1.push(item)
            s2.push(item)
          }
        }
      }
      (IO.cede *> one).replicateA_(R)
    }
    def pop1(): Unit = {
      for (_ <- 1 to B) {
        atomic { implicit txn =>
          val i1 = s1.tryPop()
          val i2 = s2.tryPop()
          (i1, i2) match {
            case (Some(v1), Some(v2)) =>
              if (v1 =!= v2) fail(s"Popped different values: ${v1} and ${v2}")
            case (None, None) =>
              // OK, empty stacks
            case _ =>
              fail(s"Popped different items: ${i1} and ${i2}")
          }
        }
      }
    }
    def pop(): IO[Unit] = {
      (IO.cede *> IO.blocking { pop1() }).replicateA_(R)
    }

    for {
      fpu1 <- push(XorShift()).start
      fpo1 <- pop().start
      fpu2 <- push(XorShift()).start
      fpo2 <- pop().start
      _ <- fpu1.join
      _ <- fpu2.join
      _ <- fpo1.join
      _ <- fpo2.join
    } yield ()
  }
}
