/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import cats.{ Eq, Semigroupal }
import cats.syntax.all._

import org.scalacheck.{ Arbitrary, Cogen, Gen }
import org.scalacheck.rng.Seed

import core.{ Ref, Ref2, Rxn, RefLike }
import stm.TRef
import internal.mcas.MemoryLocation

trait TestInstancesCore extends TestInstancesCoreLowPrio { self =>

  implicit def arbRef[A](implicit arbA: Arbitrary[A]): Arbitrary[Ref[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      Gen.oneOf(
        Gen.oneOf(
          genDelay(Ref.unsafe(a, AllocationStrategy.Unpadded, this.rigInstance)),
          genDelay(Ref.unsafe(a, AllocationStrategy.Padded, this.rigInstance)),
        ),
        Gen.oneOf(
          genDelay(Ref2.p1p1[A, String](a, "foo").unsafePerform(this.mcasImpl)._1),
          genDelay(Ref2.p1p1[String, A]("foo", a).unsafePerform(this.mcasImpl)._2),
          genDelay(Ref2.p2[A, String](a, "foo").unsafePerform(this.mcasImpl)._1),
          genDelay(Ref2.p2[String, A]("foo", a).unsafePerform(this.mcasImpl)._2),
        ),
        Gen.choose(1, 8).flatMap { s =>
          Arbitrary.arbBool.arbitrary.flatMap { sparse =>
            Arbitrary.arbBool.arbitrary.flatMap { flat =>
              val str = AllocationStrategy(sparse = sparse, flat = flat, padded = false)
              genDelay { Ref.unsafeArray[A](size = s, initial = a, str = str, rig = this.rigInstance) }.flatMap { arr =>
                Gen.oneOf(Gen.const(0), Gen.choose(0, s - 1)).flatMap { idx =>
                  genDelay { arr.getOrCreateRefOrNull(idx) }
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

  implicit def arbTRef[A](implicit arbA: Arbitrary[A]): Arbitrary[TRef[A]] = Arbitrary {
    arbA.arbitrary.flatMap { a =>
      genDelay(TRef.unsafeRefWithId(a, this.rigInstance.nextId()))
    }
  }

  implicit def cogenTRef[A]: Cogen[TRef[A]] = {
    Cogen.cogenLong.contramap[TRef[A]] { tref =>
      tref.refImpl.loc.id
    }
  }

  implicit def arbMemLoc[A](implicit arbA: Arbitrary[A]): Arbitrary[MemoryLocation[A]] = Arbitrary {
    arbRef[A].arbitrary.map(_.loc)
  }

  implicit def cogenMemLoc[A]: Cogen[MemoryLocation[A]] = {
    Cogen.cogenLong.contramap[MemoryLocation[A]](_.id)
  }

  implicit def arbAxn[B](
    implicit
    arbB: Arbitrary[B],
  ): Arbitrary[Rxn[B]] = {
    implicit val arbAny: Arbitrary[Any] = this.arbAny
    implicit val cogAny: Cogen[Any] = Cogen.cogenInt.contramap[Any](_.##)
    Arbitrary { self.arbRxn[Any, B].arbitrary }
  }

  private[choam] final def arbAny: Arbitrary[Any] = Arbitrary(
    Gen.oneOf(
      Gen.const(()),
      Gen.const(null),
      Gen.alphaNumStr,
      Gen.long,
    )
  )

  private[choam] final def unsafePerformForTest[B](rxn: Rxn[B]): B = {
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
        genDelay {
          val ref = Ref.unsafe(b, AllocationStrategy.Padded, this.rigInstance)
          ResetRxn(ref.get, Set(ResetRef(ref, b)))
        }
      },
      arbB.arbitrary.flatMap { b =>
        genDelay {
          val ref = Ref.unsafe(b, AllocationStrategy.Padded, this.rigInstance)
          ResetRxn(Rxn.unsafe.directRead(ref), Set(ResetRef(ref, b)))
        }
      },
      for {
        ab <- arbAB.arbitrary
        a0 <- arbA.arbitrary
        aa <- arbAA.arbitrary
        ref <- genDelay { Ref.unsafe(a0, AllocationStrategy.Padded, this.rigInstance) }
      } yield {
        val rxn = ref.modify[B] { aOld => (aa(aOld), ab(aOld)) }
        ResetRxn(rxn, Set(ResetRef(ref, a0)))
      },
      Gen.lzy {
        for {
          r <- arbResetRxn[A, B].arbitrary
          b <- arbB.arbitrary
          b2 <- arbB.arbitrary
          ref <- genDelay { Ref.unsafe[B](b, AllocationStrategy.Padded, this.rigInstance) }
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

trait TestInstancesCoreLowPrio extends TestInstancesBase { this: TestInstancesCore =>

  implicit def arbRefLike[A](implicit arbA: Arbitrary[A], arbAA: Arbitrary[A => A]): Arbitrary[RefLike[A]] = Arbitrary {
    Gen.oneOf(
      this.arbRef[A].arbitrary.map(r => r),
      for {
        f1 <- arbAA.arbitrary
        f2 <- arbAA.arbitrary
        ref <- this.arbRef[A].arbitrary
      } yield (ref: RefLike[A]).imap(f1)(f2),
      for {
        ref1 <- this.arbRef[A].arbitrary
        ref2 <- this.arbRef[A].arbitrary
      } yield Semigroupal[RefLike].product(ref1, ref2).imap(_._1)(a => (a, nullOf[A]))
    )
  }

  implicit def eqRefLike[A](implicit arbFunc: Arbitrary[A => A], A: Eq[A]): Eq[RefLike[A]] = { (x: RefLike[A], y: RefLike[A]) =>
    def go(seed: Long, triesLeft: Int): Boolean = {
      if (triesLeft < 1) {
        throw new AssertionError("arbFunc always returns its argument unchanged")
      }
      val f: A => A = arbFunc.arbitrary.pureApply(Gen.Parameters.default, Seed(seed))
      val oldX = this.unsafePerformForTest(x.get)
      val oldY = this.unsafePerformForTest(y.get)
      if (A.eqv(oldX, oldY)) {
        val fx = f(oldX)
        if (A.eqv(oldX, fx)) {
          go(seed + 1L, triesLeft - 1)
        } else {
          this.unsafePerformForTest(x.set(fx))
          val newX = this.unsafePerformForTest(x.get)
          val newY = this.unsafePerformForTest(y.get)
          A.eqv(newY, newX)
        }
      } else {
        false
      }
    }

    go(seed = 42L, triesLeft = 1000)
  }
}
