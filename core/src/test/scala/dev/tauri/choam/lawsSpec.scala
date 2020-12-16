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

import cats.Eq
import cats.laws.discipline.{ ArrowTests, MonadTests }
import cats.implicits._

import org.scalacheck.{ Gen, Arbitrary }

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.Configuration

import org.typelevel.discipline.scalatest.FunSuiteDiscipline

import kcas._

final class LawsSpecNaiveKCAS
  extends LawsSpec
  with SpecNaiveKCAS

final class LawsSpecMCAS
  extends LawsSpec
  with SpecMCAS

final class LawsSpecEMCAS
  extends LawsSpec
  with SpecEMCAS

abstract class LawsSpec extends AnyFunSuite with Configuration with FunSuiteDiscipline with KCASImplSpec {

  implicit def arbReact[A, B](implicit arbA: Arbitrary[A], arbB: Arbitrary[B], arbAB: Arbitrary[A => B]): Arbitrary[React[A, B]] = Arbitrary {
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
          a2 <- arbA.arbitrary
        } yield {
          a2.## // TODO: unused
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
        } yield {
          val ref = Ref.mk[B](b)
          r.postCommit(ref.upd[B, Unit] { case (_, b) => (b, ()) })
        }
      }
    )
  }

  implicit def sketchyEq[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[React[A, B]] = new Eq[React[A, B]] {
    def eqv(x: React[A, B], y: React[A, B]): Boolean = {
      (1 to 1000).forall { _ =>
        val a = arbA.arbitrary.sample.getOrElse(fail())
        val bx = x.unsafePerform(a)
        val by = y.unsafePerform(a)
        equB.eqv(bx, by)
      }
    }
  }

  checkAll("Arrow[React]", ArrowTests[React].arrow[Int, Int, Int, Int, Int, Int])
  checkAll("Monad[React]", MonadTests[React[String, *]].monad[Int, String, Int])
}
