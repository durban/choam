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

trait ReactiveAsyncLawTests[F[_]] extends ReactiveLawTests[F] {

  implicit override def reactiveInstance: Reactive.Async[F]

  override def laws: ReactiveAsyncLaws[F] =
    ReactiveAsyncLaws[F]

  def reactiveAsync[A, B](
    implicit
    arbA: Arbitrary[A],
    arbAB: Arbitrary[A => B],
    arbAxB: Arbitrary[A =#> B],
    equFA: Eq[F[A]],
    equFB: Eq[F[B]],
    equFBoolA: Eq[F[(Boolean, A)]],
  ): RuleSet = new DefaultRuleSet(
    name = "Reactive.Async",
    parent = Some(this.reactive[A, B]),
    "promise complete then get" -> forAll(laws.promiseCompleteAndGet[A] _),
  )
}

object ReactiveAsyncLawTests {
  def apply[F[_]](implicit rF: Reactive.Async[F]): ReactiveAsyncLawTests[F] = {
    new ReactiveAsyncLawTests[F] {
      final override def reactiveInstance: Reactive.Async[F] =
        rF
    }
  }
}
