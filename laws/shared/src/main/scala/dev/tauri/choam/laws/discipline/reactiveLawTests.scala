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

import core.{ Rxn, Reactive, AsyncReactive }

sealed trait ReactiveLawTests[F[_]] extends Laws {

  implicit def reactiveInstance: Reactive[F]

  def laws: ReactiveLaws[F] =
    ReactiveLaws[F]

  def reactive[A, B](
    implicit
    arbA: Arbitrary[A],
    arbAB: Arbitrary[A => B],
    arbRxnA: Arbitrary[Rxn[A]],
    equFA: Eq[F[A]],
    equFB: Eq[F[B]],
  ): RuleSet = new DefaultRuleSet(
    name = "Reactive",
    parent = None, // TODO: monad
    "run pure" -> forAll(laws.runPure[A]),
    "map in Rxn and F" -> forAll(laws.mapInRxnAndF[A, B])
  )
}

object ReactiveLawTests {
  def apply[F[_]](implicit rF: Reactive[F]): ReactiveLawTests[F] = {
    new ReactiveLawTests[F] {
      final override def reactiveInstance: Reactive[F] =
        rF
    }
  }
}

sealed trait AsyncReactiveLawTests[F[_]] extends ReactiveLawTests[F] {

  implicit override def reactiveInstance: AsyncReactive[F]

  override def laws: AsyncReactiveLaws[F] =
    AsyncReactiveLaws[F]

  def asyncReactive[A, B](
    implicit
    arbA: Arbitrary[A],
    arbAB: Arbitrary[A => B],
    arbRxnA: Arbitrary[Rxn[A]],
    equFA: Eq[F[A]],
    equFB: Eq[F[B]],
    equFBoolA: Eq[F[(Boolean, A)]],
  ): RuleSet = new DefaultRuleSet(
    name = "Reactive.Async",
    parent = Some(this.reactive[A, B]),
    "promise complete then get" -> forAll(laws.promiseCompleteAndGet[A]),
  )
}

object AsyncReactiveLawTests {
  def apply[F[_]](implicit rF: AsyncReactive[F]): AsyncReactiveLawTests[F] = {
    new AsyncReactiveLawTests[F] {
      final override def reactiveInstance: AsyncReactive[F] =
        rF
    }
  }
}
