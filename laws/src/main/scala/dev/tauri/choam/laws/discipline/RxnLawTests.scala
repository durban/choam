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

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

trait RxnLawTests extends Laws {

  def laws: RxnLaws =
    new RxnLaws {}

  def rxn[A, B, C](
    implicit
    arbA: Arbitrary[A],
    arbC: Arbitrary[C],
    equAUnit: Eq[Rxn[A, Unit]],
    equAC: Eq[Rxn[A, C]],
    equAnyA: Eq[Rxn[Any, A]],
    equAnyB: Eq[Rxn[Any, B]],
    arbAB: Arbitrary[Rxn[A, B]],
  ): RuleSet = new DefaultRuleSet(
    name = "rxn",
    parent = None,
    "as is map" -> forAll(laws.asIsMap[A, B, C] _),
    "void is map" -> forAll(laws.voidIsMap[A, B] _),
    "provide is contramap" -> forAll(laws.provideIsContramap[A, B] _),
    "pure is ret" -> forAll(laws.pureIsRet[A] _),
  )
}
