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
package laws

import cats.Eq
import cats.data.Ior
import cats.implicits._

import org.scalacheck.{ Gen, Arbitrary, Cogen }

import mcas.ImpossibleOperation

trait TestInstances extends TestInstancesLowPrio0 { self =>

  def kcasImpl: mcas.MCAS

  implicit def arbRef[A](implicit arbA: Arbitrary[A]): Arbitrary[Ref[A]] = Arbitrary {
    import refs.Ref2
    arbA.arbitrary.flatMap { a =>
      Gen.oneOf(
        Gen.oneOf(
          Gen.delay(Ref.unsafeUnpadded(a)),
          Gen.delay(Ref.unsafePadded(a)),
        ),
        Gen.oneOf(
          Gen.delay(Ref2.unsafeP1P1[A, String](a, "foo")._1),
          Gen.delay(Ref2.unsafeP1P1[String, A]("foo", a)._2),
          Gen.delay(Ref2.unsafeP2[A, String](a, "foo")._1),
          Gen.delay(Ref2.unsafeP2[String, A]("foo", a)._2),
        ),
        Gen.oneOf(
          Gen.choose(1, 8).flatMap { s =>
            Gen.delay(Ref.unsafeStrictArray[A](size = s, initial = a).apply(0))
          },
          Gen.choose(1, 8).flatMap { s =>
            Gen.choose(0, s - 1).flatMap { i =>
              Gen.delay(Ref.unsafeStrictArray[A](size = s, initial = a).apply(i))
            }
          },
          Gen.choose(1, 8).flatMap { s =>
            Gen.delay(Ref.unsafeLazyArray[A](size = s, initial = a).apply(0))
          },
          Gen.choose(1, 8).flatMap { s =>
            Gen.choose(0, s - 1).flatMap { i =>
              Gen.delay(Ref.unsafeLazyArray[A](size = s, initial = a).apply(i))
            }
          },
        ),
      )
    }
  }

  implicit def cogenRef[A]: Cogen[Ref[A]] = {
    Cogen.cogenList[Long].contramap[Ref[A]] { ref =>
      List(ref.loc.id0, ref.loc.id1, ref.loc.id2, ref.loc.id3)
    }
  }

  implicit def arbAxn[B](
    implicit
    arbB: Arbitrary[B],
  ): Arbitrary[Axn[B]] = {
    implicit val arbAny: Arbitrary[Any] = Arbitrary(
      Gen.oneOf(
        Gen.const(()),
        Gen.const(null),
        Gen.alphaNumStr,
      )
    )
    implicit val cogAny: Cogen[Any] = Cogen.cogenInt.contramap[Any](_.##)
    Arbitrary { self.arbRxn[Any, B].arbitrary }
  }

  implicit def arbIor[A, B](implicit arbA: Arbitrary[A], arbB: Arbitrary[B]): Arbitrary[Ior[A, B]] = Arbitrary {
    Gen.oneOf(
      arbA.arbitrary.map(Ior.left),
      arbB.arbitrary.map(Ior.right),
      arbA.arbitrary.flatMap { a => arbB.arbitrary.map { b => Ior.both(a, b) } },
    )
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

  implicit def arbRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[Rxn[A, B]] = Arbitrary {
    arbResetRxn[A, B].arbitrary.map(_.toRxn)
  }

  private def arbResetRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[ResetRxn[A, B]] = Arbitrary {
    Gen.oneOf(
      arbAB.arbitrary.map(ab => ResetRxn(Rxn.lift[A, B](ab))),
      arbB.arbitrary.map(b => ResetRxn(Rxn.ret(b))),
      Gen.lzy {
        arbResetRxn[A, B].arbitrary.map { rxn =>
          ResetRxn(Rxn.identity[A]) >>> rxn
        }
      },
      Gen.lzy {
        for {
          one <- arbResetRxn[A, B].arbitrary
          two <- arbResetRxn[A, B].arbitrary
        } yield one + two
      },
      Gen.lzy {
        for {
          one <- arbResetRxn[A, A].arbitrary
          two <- arbResetRxn[A, B].arbitrary
        } yield one >>> two
      },
      Gen.lzy {
        for {
          flag <- Arbitrary.arbBool.arbitrary
          one <- arbResetRxn[A, B].arbitrary
          two <- arbResetRxn[A, B].arbitrary
        } yield (one * two).map { bb => if (flag) bb._1 else bb._2 }
      },
      arbB.arbitrary.flatMap { b =>
        Gen.delay {
          val ref = Ref.unsafe(b)
          ResetRxn(ref.unsafeInvisibleRead, Set(ResetRef(ref, b)))
        }
      },
      for {
        ab <- arbAB.arbitrary
        a0 <- arbA.arbitrary
        ref <- Gen.delay { Ref.unsafe(a0) }
      } yield {
        val rxn = ref.upd[A, B] { (aOld, aInput) => (aInput, ab(aOld)) }
        ResetRxn(rxn, Set(ResetRef(ref, a0)))
      },
      // TODO: this generates `r: Rxn[A, B]` so that `r * r` can never commit
      // for {
      //   aa <- arbAA.arbitrary
      //   ab <- arbAB.arbitrary
      //   a0 <- arbA.arbitrary
      //   ref <- Gen.delay { Ref.unsafe(a0) }
      // } yield {
      //   val rxn = ref.unsafeInvisibleRead.flatMap { oldA =>
      //     val newA = aa(oldA)
      //     ref.unsafeCas(oldA, newA).as(ab(newA))
      //   }
      //   ResetRxn(rxn, Set(ResetRef(ref, a0)))
      // },
      Gen.lzy {
        for {
          r <- arbResetRxn[A, B].arbitrary
          b <- arbB.arbitrary
          b2 <- arbB.arbitrary
          ref <- Gen.delay { Ref.unsafe[B](b) }
        } yield {
          ResetRxn(
            r.rxn.postCommit(ref.upd[B, Unit] { case (_, _) => (b2, ()) }),
            r.refs + ResetRef(ref, b)
          )
        }
      },
      Gen.lzy {
        for {
          a <- arbA.arbitrary
          aa <- arbAA.arbitrary
          r <- arbResetRxn[A, B].arbitrary
          ref <- Gen.delay { Ref.unsafe[A](a) }
        } yield {
          ResetRxn(
            Rxn.unsafe.delayComputed(prepare = r.rxn.map(b => ref.update(aa).as(b))),
            r.refs + ResetRef(ref, a)
          )
        }
      },
      Gen.lzy {
        arbResetRxn[A, B].arbitrary.map { rxn =>
          ResetRxn(Rxn.unsafe.retry[A, B]) + rxn
        }
      },
      Gen.lzy {
        Arbitrary.arbString.arbitrary.flatMap { s =>
          arbResetRxn[A, B].arbitrary.map { rr =>
            ResetRxn(
              rr.rxn.first[String].contramap[A](a => (a, s)).map(_._1),
              rr.refs
            )
          }
        }
      },
    )
  }

  implicit def testingEqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]] = new Eq[Rxn[A, B]] {
    override def eqv(x: Rxn[A, B], y: Rxn[A, B]): Boolean = {
      (1 to 32).forall { _ =>
        val a = arbA.arbitrary.sample.getOrElse {
          throw new IllegalStateException("no sample")
        }
        val rx = self.unsafePerformForTest(x, a)
        val ry = self.unsafePerformForTest(y, a)
        Eq[Either[ImpossibleOperation, B]].eqv(rx, ry)
      }
    }
  }

  implicit def testingEqImpossibleOp: Eq[ImpossibleOperation] = { (x, y) =>
    x.equiv(y)
  }
}

private[choam] sealed trait TestInstancesLowPrio1 { self: TestInstances =>
}
