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
package laws

import cats.Eq
import cats.data.Ior

import org.scalacheck.{ Gen, Arbitrary, Cogen }

import core.{ Rxn, Ref, Ref2 }
import internal.mcas.{ Mcas, RefIdGen }

trait TestInstances { self =>

  def mcasImpl: Mcas

  protected def rigInstance: RefIdGen

  implicit def arbRef[A](implicit arbA: Arbitrary[A]): Arbitrary[Ref[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      Gen.oneOf(
        Gen.oneOf(
          Gen.delay(Ref.unsafe(a, Ref.AllocationStrategy.Unpadded, this.rigInstance)),
          Gen.delay(Ref.unsafe(a, Ref.AllocationStrategy.Padded, this.rigInstance)),
        ),
        Gen.oneOf(
          Gen.delay(Ref2.p1p1[A, String](a, "foo").unsafePerform(this.mcasImpl)._1),
          Gen.delay(Ref2.p1p1[String, A]("foo", a).unsafePerform(this.mcasImpl)._2),
          Gen.delay(Ref2.p2[A, String](a, "foo").unsafePerform(this.mcasImpl)._1),
          Gen.delay(Ref2.p2[String, A]("foo", a).unsafePerform(this.mcasImpl)._2),
        ),
        Gen.choose(1, 8).flatMap { s =>
          Arbitrary.arbBool.arbitrary.flatMap { sparse =>
            Arbitrary.arbBool.arbitrary.flatMap { flat =>
              val str = Ref.Array.AllocationStrategy(sparse = sparse, flat = flat, padded = false)
              Gen.delay { Ref.unsafeArray[A](size = s, initial = a, str = str, rig = this.rigInstance) }.flatMap { arr =>
                Gen.oneOf(Gen.const(0), Gen.choose(0, s - 1)).flatMap { idx =>
                  Gen.delay { arr.unsafeApply(idx) }
                }
              }
            }
          }
        }
      )
    }
  }

  implicit def cogenRef[A]: Cogen[Ref[A]] = {
    Cogen.cogenLong.contramap[Ref[A]] { ref =>
      ref.loc.id
    }
  }

  implicit def arbAxn[B](
    implicit
    arbB: Arbitrary[B],
  ): Arbitrary[Rxn[B]] = {
    implicit val arbAny: Arbitrary[Any] = Arbitrary(
      Gen.oneOf(
        Gen.const(()),
        Gen.const(null),
        Gen.alphaNumStr,
        Gen.long,
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

  implicit def cogenIor[A, B](implicit cogA: Cogen[A], cogB: Cogen[B]): Cogen[Ior[A, B]] = {
    Cogen { (seed, ior) =>
      ior.fold(
        a => cogA.perturb(seed.next, a),
        b => cogB.perturb(seed.next.next, b),
        (a, b) => cogB.perturb(cogA.perturb(seed.next.next.next, a), b)
      )
    }
  }

  private[choam] final def unsafePerformForTest[A, B](rxn: Rxn[B]): B = {
    rxn.unsafePerform(self.mcasImpl)
  }

  private def arbRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[Rxn[B]] = Arbitrary {
    arbResetRxn[A, B].arbitrary.map(_.toRxn)
  }

  private def arbResetRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[ResetRxn[B]] = Arbitrary {
    // Note: if too much of the choices (in the `oneOf`)
    // calls recursively `arbResetRxn`, then scalacheck
    // will (with high probability) recurse very deeply
    // during the generation, which either takes a lot of
    // time, or even can overflow the stack. This is why
    // we have a few very trivial `Rxn`s among the choices.
    Gen.oneOf(
      arbB.arbitrary.map(b => ResetRxn(Rxn.pure(b))),
      arbB.arbitrary.map(b => ResetRxn(Rxn.unsafe.delay { b })),
      arbB.arbitrary.map(b => ResetRxn(Rxn.unit.as(b))),
      Gen.lzy {
        arbResetRxn[A, B].arbitrary.map { rxn =>
          ResetRxn(Rxn.unit) *> rxn
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
        } yield one *> two
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
          val ref = Ref.unsafe(b, Ref.AllocationStrategy.Padded, this.rigInstance)
          ResetRxn(ref.get, Set(ResetRef(ref, b)))
        }
      },
      arbB.arbitrary.flatMap { b =>
        Gen.delay {
          val ref = Ref.unsafe(b, Ref.AllocationStrategy.Padded, this.rigInstance)
          ResetRxn(Rxn.unsafe.directRead(ref), Set(ResetRef(ref, b)))
        }
      },
      for {
        ab <- arbAB.arbitrary
        a0 <- arbA.arbitrary
        aa <- arbAA.arbitrary
        ref <- Gen.delay { Ref.unsafe(a0, Ref.AllocationStrategy.Padded, this.rigInstance) }
      } yield {
        val rxn = ref.modify[B] { aOld => (aa(aOld), ab(aOld)) }
        ResetRxn(rxn, Set(ResetRef(ref, a0)))
      },
      // TODO: this generates `r: Rxn[A, B]` so that `r * r` can never commit
      // for {
      //   aa <- arbAA.arbitrary
      //   ab <- arbAB.arbitrary
      //   a0 <- arbA.arbitrary
      //   ref <- Gen.delay { Ref.unsafe(a0) }
      // } yield {
      //   val rxn = ref.unsafeDirectRead.flatMap { oldA =>
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
          ref <- Gen.delay { Ref.unsafe[B](b, Ref.AllocationStrategy.Padded, this.rigInstance) }
        } yield {
          ResetRxn(
            r.rxn.postCommit(ref.set(b2)),
            r.refs + ResetRef(ref, b)
          )
        }
      },
      Gen.lzy {
        arbResetRxn[A, B].arbitrary.map { rxn =>
          ResetRxn(Rxn.unsafe.retry[B]) + rxn
        }
      },
    )
  }

  implicit def testingEqRxn[B](implicit equB: Eq[B]): Eq[Rxn[B]] = new Eq[Rxn[B]] {
    override def eqv(x: Rxn[B], y: Rxn[B]): Boolean = {
      // Note: we only generate `Rxn`s with deterministic results
      val rx1 = self.unsafePerformForTest(x)
      val ry1 = self.unsafePerformForTest(y)
      val rx2 = self.unsafePerformForTest(x)
      val ry2 = self.unsafePerformForTest(y)
      equB.eqv(rx1, ry1) && equB.eqv(rx1, rx2) && equB.eqv(rx2, ry2)
    }
  }
}
