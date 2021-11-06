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
package discipline

import cats.kernel.Eq
import cats.kernel.laws.discipline.catsLawsIsEqToProp

import org.scalacheck.{ Arbitrary, Cogen }
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

object RxnLawTests {

  def apply(ti: TestInstances): RxnLawTests = new RxnLawTests {
    override def eqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]] =
      ti.testingEqAxn[A]
    override def eqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]] =
      ti.testingEqRxn[A, B]
    override def arbRxn[A, B](implicit arbA: Arbitrary[A], arbB: Arbitrary[B], arbAB: Arbitrary[A => B], arbAA: Arbitrary[A => A]): Arbitrary[Rxn[A, B]] =
      ti.arbRxn[A, B]
  }
}

trait RxnLawTests extends Laws {

  implicit def eqAxn[A](implicit equA: Eq[A]): Eq[Axn[A]]

  implicit def eqRxn[A, B](implicit arbA: Arbitrary[A], equB: Eq[B]): Eq[Rxn[A, B]]

  implicit def arbRxn[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbAB: Arbitrary[A => B],
    arbAA: Arbitrary[A => A]
  ): Arbitrary[Rxn[A, B]]

  def laws: RxnLaws =
    new RxnLaws {}

  def rxn[A, B, C, D](
    implicit
    equA: Eq[A],
    equB: Eq[B],
    equC: Eq[C],
    equD: Eq[D],
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
    arbC: Arbitrary[C],
    arbD: Arbitrary[D],
    cogA: Cogen[A],
    cogB: Cogen[B],
    cogC: Cogen[C],
  ): RuleSet = new DefaultRuleSet(
    name = "rxn",
    parent = None,
    "as is map" -> forAll(laws.asIsMap[A, B, C] _),
    "void is map" -> forAll(laws.voidIsMap[A, B] _),
    "provide is contramap" -> forAll(laws.provideIsContramap[A, B] _),
    "pure is ret" -> forAll(laws.pureIsRet[A] _),
    "toFunction is provide" -> forAll(laws.toFunctionIsProvide[A, B] _),
    "map is >>> lift" -> forAll(laws.mapIsAndThenLift[A, B, C] _),
    "contramap is lift >>>" -> forAll(laws.contramapIsLiftAndThen[A, B, C] _),
    "* is ×" -> forAll(laws.timesIsAndAlso[A, B, C] _),
    "× is >>>" -> forAll(laws.andAlsoIsAndThen[A, B, C, D] _),
  )
}
