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
package laws
package discipline

import cats.kernel.Eq
import cats.kernel.laws.discipline.catsLawsIsEqToProp

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

sealed trait RxnLawTests extends Laws { this: TestInstances =>

  // TODO: I gave up:

  // implicit def eqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]]

  // implicit def eqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]]

  // implicit def arbAxn[B](
  //   implicit
  //   arbB: Arbitrary[B],
  // ): Arbitrary[Axn[B]]

  // implicit def arbRxn[A, B](
  //   implicit
  //   arbA: Arbitrary[A],
  //   arbB: Arbitrary[B],
  //   arbAB: Arbitrary[A => B],
  //   arbAA: Arbitrary[A => A]
  // ): Arbitrary[Rxn[A, B]]

  def laws: RxnLaws =
    RxnLaws.newRxnLaws

  def rxn[B, C, D, F](
    implicit
    equB: Eq[B],
    equC: Eq[C],
    equD: Eq[D],
    equF: Eq[F],
    arbB: Arbitrary[B],
    arbC: Arbitrary[C],
    arbD: Arbitrary[D],
    arbF: Arbitrary[F],
  ): RuleSet = new DefaultRuleSet(
    name = "rxn",
    parent = None,
    "equals itself" -> forAll(laws.equalsItself[B]),
    "as is map" -> forAll(laws.asIsMap[B, C]),
    "void is map" -> forAll(laws.voidIsMap[B]),
    "distributive (>>> and +) 1" -> forAll(laws.distributiveAndThenChoice1[B, C]),
    "distributive (>>> and +) 2" -> forAll(laws.distributiveAndThenChoice2[B, C]),
    "disributive (× and +) 1" -> forAll(laws.distributiveAndAlsoChoice1[B, D]),
    "disributive (× and +) 2" -> forAll(laws.distributiveAndAlsoChoice2[B, D]),
    "associative ×" -> forAll(laws.associativeAndAlso[B, D, F]),
    "retry is neutral for choice (left)" -> forAll(laws.choiceRetryNeutralLeft[B]),
    "retry is neutral for choice (right)" -> forAll(laws.choiceRetryNeutralRight[B]),
  )
}

object RxnLawTests {
  private[laws] trait UnsealedRxnLawTests extends RxnLawTests { this: TestInstances =>
  }
}