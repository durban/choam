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
package laws

import cats.Eq
import cats.implicits._

import org.scalacheck.{ Gen, Arbitrary }

trait TestInstances extends TestInstancesLowPrio0 { self =>

  def kcasImpl: kcas.KCAS

  implicit def arbRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[Rxn[A, B]] = Arbitrary {
    Gen.oneOf(
      arbAB.arbitrary.map(ab => Rxn.lift[A, B](ab)),
      arbAB.arbitrary.map(ab => Rxn.identity[A] >>> Rxn.lift[A, B](ab)),
      Gen.lzy {
        for {
          one <- arbRxn[A, B].arbitrary
          two <- arbRxn[A, B].arbitrary
        } yield one + two
      },
      Gen.lzy {
        arbB.arbitrary.map { b =>
          val ref = Ref.unsafe(b)
          ref.unsafeInvisibleRead
        }
      },
      Gen.lzy {
        for {
          ab <- arbAB.arbitrary
          a1 <- arbA.arbitrary
        } yield {
          val r = Ref(a1).first[A].flatMap { case (ref, _) =>
            ref.upd[(Unit, A), B] { (a1, a2) => (a2._2, ab(a1)) }
          }
          r.lmap[A](a => ((), a))
        }
      },
      arbAB.arbitrary.map { fab =>
        val s = "x"
        val ref = Ref.unsafe(s)
        (Rxn.lift[A, B](fab) × ref.unsafeCas(s, s)).lmap[A](a => (a, ())).rmap(_._1)
      },
      Gen.lzy {
        for {
          r <- arbRxn[A, B].arbitrary
          b <- arbB.arbitrary
          b2 <- arbB.arbitrary
        } yield {
          val ref = Ref.unsafe[B](b)
          r.postCommit(ref.upd[B, Unit] { case (_, _) => (b2, ()) })
        }
      },
      Gen.lzy {
        for {
          a <- arbA.arbitrary
          aa <- arbAA.arbitrary
          r <- arbRxn[A, B].arbitrary
        } yield {
          val ref = Ref.unsafe[A](a)
          Rxn.unsafe.delayComputed(prepare = r.map(b => ref.update(aa).as(b)))
        }
      }
    )
  }

  implicit def testingEqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]] = new Eq[Axn[A]] {
    override def eqv(x: Axn[A], y: Axn[A]): Boolean = {
      val ax = x.unsafePerform((), self.kcasImpl)
      val ay = y.unsafePerform((), self.kcasImpl)
      equA.eqv(ax, ay)
    }
  }
}

private[choam] sealed trait TestInstancesLowPrio0 { self: TestInstances =>

  implicit def testingEqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]] = new Eq[Rxn[A, B]] {
    override def eqv(x: Rxn[A, B], y: Rxn[A, B]): Boolean = {
      (1 to 1000).forall { _ =>
        val a = arbA.arbitrary.sample.getOrElse {
          throw new IllegalStateException("no sample")
        }
        val bx = x.unsafePerform(a, self.kcasImpl)
        val by = y.unsafePerform(a, self.kcasImpl)
        equB.eqv(bx, by)
      }
    }
  }
}
