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

import cats.kernel.laws.discipline.catsLawsIsEqToProp

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

object RefLawTests {

  def apply(ti: TestInstances): RefLawTests = new RefLawTests {
    final override def arbRef[A : Arbitrary] =
      ti.arbRef
  }
}

trait RefLawTests extends Laws {

  implicit def arbRef[A : Arbitrary]: Arbitrary[Ref[A]]

  def laws: RefLaws =
    new RefLaws {}

  def ref[A, B](
    implicit
    arbA: Arbitrary[A],
    arbB: Arbitrary[B],
  ): RuleSet = new DefaultRuleSet(
    name = "ref",
    parent = None,
    "equals itself" -> forAll(laws.equalsItself[A] _),
    "unique IDs (same type)" -> forAll(laws.uniqueIdsSameType[A] _),
    "unique IDs (different type)" -> forAll(laws.uniqueIdsDifferentType[A, B] _),
    "Order consistent with identity" -> forAll(laws.orderConsistentWithIdentity[A] _),
  )
}