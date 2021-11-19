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

import cats.implicits._
import cats.kernel.laws.discipline.{ SemigroupTests, MonoidTests }
import cats.laws.discipline.DeferTests
import cats.effect.kernel.testkit.TestContext
import cats.effect.laws.UniqueTests
import cats.effect.{ IO, SyncIO }
import cats.laws.discipline.{ ArrowChoiceTests, MonadTests, MonoidKTests, AlignTests }
import cats.mtl.laws.discipline.LocalTests

import org.scalacheck.Prop
import munit.DisciplineSuite

final class LawsSpecNaiveKCAS
  extends LawsSpec
  with SpecNaiveKCAS

final class LawsSpecEMCAS
  extends LawsSpec
  with SpecEMCAS

trait LawsSpec
  extends DisciplineSuite
  with TestInstances
  with cats.effect.testkit.TestInstances { self: KCASImplSpec =>

  val tc: TestContext =
    TestContext()

  implicit val ticker: Ticker =
    Ticker(tc)

  checkAll("Rxn", RxnLawTests(self).rxn[String, Int, Float, Double, Boolean, Long])
  checkAll("Reactive", ReactiveLawTests[SyncIO].reactive[String, Int])
  checkAll("Reactive.Async", ReactiveAsyncLawTests[IO].reactiveAsync[String, Int])

  checkAll("ArrowChoice[Rxn]", ArrowChoiceTests[Rxn].arrowChoice[Int, Int, Int, Int, Int, Int])
  checkAll("Local[Rxn]", LocalTests[Rxn[String, *], String].local[Int, Float])
  checkAll("Monad[Rxn]", MonadTests[Rxn[String, *]].monad[Int, String, Int])
  checkAll("Unique[Rxn]", UniqueTests[Rxn[Any, *]].unique { (act: Axn[Boolean]) =>
    Prop(act.unsafeRun(self.kcasImpl))
  })
  checkAll("MonoidK[Rxn]", MonoidKTests[λ[a => Rxn[a, a]]].monoidK[String])
  checkAll("Semigroup[Rxn]", SemigroupTests[Rxn[String, Int]](Rxn.choiceSemigroup).semigroup)
  checkAll("Monoid[Rxn]", MonoidTests[Rxn[String, Int]](Rxn.monoidInstance).monoid)
  checkAll("Defer[Rxn]", DeferTests[Rxn[String, *]].defer[Int])
  checkAll("Align[Rxn]", AlignTests[Rxn[String, *]].align[Int, Float, Double, Long])
}
