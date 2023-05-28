/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
import cats.kernel.laws.discipline.{ SemigroupTests, MonoidTests, OrderTests, HashTests }
import cats.laws.discipline.{ ArrowChoiceTests, DeferTests, MonadTests, MonoidKTests, AlignTests }
import cats.effect.kernel.testkit.TestContext
import cats.effect.laws.{ UniqueTests, ClockTests }
import cats.effect.{ IO, SyncIO }

import cats.mtl.laws.discipline.LocalTests

import org.scalacheck.Prop
import munit.DisciplineSuite

import mcas.Mcas
import async.AsyncReactive

final class LawsSpecThreadConfinedMcas
  extends LawsSpec
  with SpecThreadConfinedMcas

trait LawsSpec
  extends DisciplineSuite
  with TestInstances
  with cats.effect.testkit.TestInstances { self: McasImplSpec =>

  val tc: TestContext =
    TestContext()

  implicit val ticker: Ticker =
    Ticker(tc)

  checkAll("Rxn", new RxnLawTests with TestInstances {
    override def mcasImpl: Mcas = self.mcasImpl
  }.rxn[String, Int, Float, Double, Boolean, Long])

  checkAll("Ref", RefLawTests(self).ref[String, Int, Float])
  checkAll("Reactive", ReactiveLawTests[SyncIO](Reactive.reactiveForSync).reactive[String, Int])
  checkAll("AsyncReactive", AsyncReactiveLawTests[IO](AsyncReactive.asyncReactiveForAsync).asyncReactive[String, Int])

  checkAll("ArrowChoice[Rxn]", ArrowChoiceTests[Rxn].arrowChoice[Int, Int, Int, Int, Int, Int])
  checkAll("Local[Rxn]", LocalTests[Rxn[String, *], String].local[Int, Float])
  checkAll("Monad[Rxn]", MonadTests[Rxn[String, *]].monad[Int, String, Int])
  checkAll("Unique[Rxn]", UniqueTests[Rxn[Any, *]].unique { (act: Axn[Boolean]) =>
    Prop(act.unsafeRun(self.mcasImpl))
  })
  checkAll("MonoidK[Rxn]", MonoidKTests[λ[a => Rxn[a, a]]].monoidK[String])
  checkAll("Semigroup[Rxn]", SemigroupTests[Rxn[String, Int]](Rxn.choiceSemigroup).semigroup)
  checkAll("Monoid[Rxn]", MonoidTests[Rxn[String, Int]](Rxn.monoidInstance).monoid)
  checkAll("Defer[Rxn]", DeferTests[Rxn[String, *]].defer[Int])
  checkAll("Align[Rxn]", AlignTests[Rxn[String, *]].align[Int, Float, Double, Long])
  checkAll("Clock[Rxn]", ClockTests[Rxn[String, *]].clock { (act: Rxn[String, Boolean]) =>
    Prop(act.unsafePerform(null : String, self.mcasImpl))
  })

  checkAll("Order[Ref[Int]]", OrderTests[Ref[Int]].order)
  checkAll("Hash[Ref[Int]]", HashTests[Ref[Int]].hash)
}
