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

import cats.Eq
import cats.laws.discipline.{ ArrowChoiceTests, MonadTests }
import cats.implicits._
import cats.mtl.laws.discipline.LocalTests

import org.scalacheck.{ Gen, Arbitrary }

import munit.DisciplineSuite

import kcas._

final class LawsSpecNaiveKCAS
  extends LawsSpec
  with SpecNaiveKCAS

final class LawsSpecEMCAS
  extends LawsSpec
  with SpecEMCAS

trait LawsSpec extends DisciplineSuite { self: KCASImplSpec =>

  implicit def arbReact[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[React[A, B]] = Arbitrary {
    Gen.oneOf(
      arbAB.arbitrary.map(ab => React.lift[A, B](ab)),
      arbAB.arbitrary.map(ab => React.identity[A] >>> React.lift[A, B](ab)),
      Gen.lzy {
        for {
          one <- arbReact[A, B].arbitrary
          two <- arbReact[A, B].arbitrary
        } yield one + two
      },
      Gen.lzy {
        arbB.arbitrary.map { b =>
          val ref = Ref.mk(b)
          ref.invisibleRead.lmap[A](_ => ())
        }
      },
      Gen.lzy {
        for {
          ab <- arbAB.arbitrary
          a1 <- arbA.arbitrary
        } yield {
          val r = React.newRef(a1).first[A].flatMap { case (ref, _) =>
            ref.upd[(Unit, A), B] { (a1, a2) => (a2._2, ab(a1)) }
          }
          r.lmap[A](a => ((), a))
        }
      },
      arbAB.arbitrary.map { fab =>
        val s = "x"
        val ref = Ref.mk(s)
        (React.lift[A, B](fab) × ref.cas(s, s)).lmap[A](a => (a, ())).rmap(_._1)
      },
      Gen.lzy {
        for {
          r <- arbReact[A, B].arbitrary
          b <- arbB.arbitrary
          b2 <- arbB.arbitrary
        } yield {
          val ref = Ref.mk[B](b)
          r.postCommit(ref.upd[B, Unit] { case (_, _) => (b2, ()) })
        }
      },
      Gen.lzy {
        for {
          a <- arbA.arbitrary
          aa <- arbAA.arbitrary
          r <- arbReact[A, B].arbitrary
        } yield {
          val ref = Ref.mk[A](a)
          React.unsafe.delayComputed(prepare = r.map(b => ref.modify(aa).map(_ => b)))
        }
      }
    )
  }

  implicit def testingEqReact[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[React[A, B]] = new Eq[React[A, B]] {
    def eqv(x: React[A, B], y: React[A, B]): Boolean = {
      (1 to 1000).forall { _ =>
        val a = arbA.arbitrary.sample.getOrElse(fail("no sample"))
        val bx = x.unsafePerform(a, self.kcasImpl)
        val by = y.unsafePerform(a, self.kcasImpl)
        equB.eqv(bx, by)
      }
    }
  }

  checkAll("ArrowChoice[React]", ArrowChoiceTests[React].arrowChoice[Int, Int, Int, Int, Int, Int])
  checkAll("Monad[React]", MonadTests[React[String, *]].monad[Int, String, Int])
  checkAll("Local[React]", LocalTests[React[String, *], String].local[Int, Float])
}
