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
import cats.data.Ior
import cats.implicits._

import org.scalacheck.{ Gen, Arbitrary, Cogen }

import kcas.ImpossibleOperation

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

  implicit def arbIor[A, B](implicit arbA: Arbitrary[A], arbB: Arbitrary[B]): Arbitrary[Ior[A, B]] = Arbitrary {
    Gen.choose(0, 2).flatMap {
      case 0 => // left
        arbA.arbitrary.map(Ior.left)
      case 1 => // right
        arbB.arbitrary.map(Ior.right)
      case 2 => // both
        arbA.arbitrary.flatMap { a => arbB.arbitrary.map { b => Ior.both(a, b) } }
    }
  }

  implicit def cogIor[A, B](implicit cogA: Cogen[A], cogB: Cogen[B]): Cogen[Ior[A, B]] = {
    Cogen { (seed, ior) =>
      ior.fold(
        a => cogA.perturb(seed.next, a),
        b => cogB.perturb(seed.next.next, b),
        (a, b) => cogB.perturb(cogA.perturb(seed, a), b)
      )
    }
  }

  implicit def testingEqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]] = new Eq[Axn[A]] {
    override def eqv(x: Axn[A], y: Axn[A]): Boolean = {
      val rx = self.unsafePerformForTest(x, ())
      val ry = self.unsafePerformForTest(y, ())
      Eq[Either[ImpossibleOperation, A]].eqv(rx, ry)
    }
  }

  private[choam] final def unsafePerformForTest[A, B](rxn: A =#> B, a: A): Either[ImpossibleOperation, B] = {
    try {
      Right(rxn.unsafePerform(a, self.kcasImpl))
    } catch { case ex: ImpossibleOperation =>
      Left(ex)
    }
  }
}

private[choam] sealed trait TestInstancesLowPrio0 extends TestInstancesLowPrio1 { self: TestInstances =>

  implicit def testingEqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]] = new Eq[Rxn[A, B]] {
    override def eqv(x: Rxn[A, B], y: Rxn[A, B]): Boolean = {
      (1 to 1000).forall { _ =>
        val a = arbA.arbitrary.sample.getOrElse {
          throw new IllegalStateException("no sample")
        }
        val rx = self.unsafePerformForTest(x, a)
        val ry = self.unsafePerformForTest(y, a)
        Eq[Either[ImpossibleOperation, B]].eqv(rx, ry)
      }
    }
  }

  implicit def testingEqImpossibleOp: Eq[ImpossibleOperation] = new Eq[ImpossibleOperation] {

    private[this] def equu[A](a1: A, a2: A): Boolean =
      a1 == a2 // universal equals

    final override def eqv(x: ImpossibleOperation, y: ImpossibleOperation): Boolean = {
      equ(x.ref, y.ref) && equu(x.a.ov, y.a.ov) && equu(x.a.nv, y.a.nv) && equu(x.b.ov, y.b.ov) && equu(x.b.nv, y.b.nv)
    }
  }
}

private[choam] sealed trait TestInstancesLowPrio1 { self: TestInstances =>
}
