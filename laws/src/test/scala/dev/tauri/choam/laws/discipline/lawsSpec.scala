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

import cats.kernel.laws.discipline.SemigroupTests
import cats.laws.discipline.{ ArrowChoiceTests, MonadTests, MonoidKTests }
import cats.implicits._
import cats.mtl.laws.discipline.LocalTests

import munit.DisciplineSuite

final class LawsSpecNaiveKCAS
  extends LawsSpec
  with SpecNaiveKCAS

final class LawsSpecEMCAS
  extends LawsSpec
  with SpecEMCAS

trait LawsSpec extends DisciplineSuite with TestInstances { self: KCASImplSpec =>

  checkAll("Rxn", new RxnLawTests {}.rxn[String, Int, Float])

  checkAll("ArrowChoice[Rxn]", ArrowChoiceTests[Rxn].arrowChoice[Int, Int, Int, Int, Int, Int])
  checkAll("Local[Rxn]", LocalTests[Rxn[String, *], String].local[Int, Float])
  checkAll("Monad[Rxn]", MonadTests[Rxn[String, *]].monad[Int, String, Int])
  checkAll("MonoidK[Rxn]", MonoidKTests[λ[a => Rxn[a, a]]].monoidK[String])
  checkAll("Semigroup[Rxn]", SemigroupTests[Rxn[String, Int]].semigroup)
}
