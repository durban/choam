/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package discipline

import cats.kernel.Eq
import cats.kernel.laws.discipline.catsLawsIsEqToProp

import org.scalacheck.{ Arbitrary, Cogen }
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

object RefLawTests {

  def apply(ti: TestInstances): RefLawTests = new RefLawTests {
    final override def arbRef[A : Arbitrary] =
      ti.arbRef
    override implicit def eqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]] =
      ti.testingEqAxn[A]
    override implicit def eqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]] =
      ti.testingEqRxn[A, B]
  }
}

sealed trait RefLawTests extends Laws {

  implicit def arbRef[A : Arbitrary]: Arbitrary[Ref[A]]

  implicit def eqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]]

  implicit def eqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]]

  def laws: RefLaws =
    RefLaws.newRefLaws

  def ref[A, B, C](
    implicit
    equC: Eq[C],
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbC: Arbitrary[C],
    cogA: Cogen[A],
    cogB: Cogen[B],
  ): RuleSet = new DefaultRuleSet(
    name = "ref",
    parent = None,
    "equals itself" -> forAll(laws.equalsItself[A] _),
    "unique IDs (same type)" -> forAll(laws.uniqueIdsSameType[A] _),
    "unique IDs (different type)" -> forAll(laws.uniqueIdsDifferentType[A, B] _),
    "Order consistent with identity" -> forAll(laws.orderConsistentWithIdentity[A] _),
    "updWith and ret is upd" -> forAll(laws.updWithRetIsUpd[A, B, C] _),
  )
}
