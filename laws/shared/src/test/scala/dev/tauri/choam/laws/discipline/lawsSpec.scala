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

import cats.implicits._
import cats.kernel.laws.discipline.{ SemigroupTests, MonoidTests, HashTests, OrderTests }
import cats.laws.discipline.{ DeferTests, MonadTests, AlignTests, InvariantTests, InvariantSemigroupalTests }
import cats.effect.kernel.testkit.TestContext
import cats.effect.laws.{ UniqueTests, ClockTests }
import cats.effect.{ IO, SyncIO }

import org.scalacheck.Prop
import munit.DisciplineSuite

import core.{ Rxn, Ref, RefLike, Reactive, AsyncReactive }
import stm.{ TRef }
import internal.mcas.{ Mcas, MemoryLocation }

final class LawsSpecThreadConfinedMcas
  extends LawsSpec
  with SpecThreadConfinedMcas {

  this.registerLaws()
}

trait LawsSpec
  extends DisciplineSuite
  with TestInstances
  with cats.effect.testkit.TestInstances { self: McasImplSpec =>

  val tc: TestContext =
    TestContext()

  implicit val ticker: Ticker =
    Ticker(tc)

  private[this] implicit lazy val reactiveSyncIOInstance: Reactive[SyncIO] =
    new Reactive.SyncReactive(this.mcasImpl)

  private[this] implicit lazy val asyncReactiveIOInstance: AsyncReactive[IO] =
    new AsyncReactive.AsyncReactiveImpl(this.mcasImpl)

  /**
   * This ugly indirection is necessary because if we
   * call `checkAll` in our constructor, it won't work,
   * since `this.mcasImpl` is initialized in the ctor
   * of one of our subclasses. So... our every (concrete)
   * subclass must call `registerLaws` in its ctor...
   */
  protected final def registerLaws(): Unit = {
    checkAll("Rxn", new RxnLawTests.UnsealedRxnLawTests with TestInstances {
      override def mcasImpl: Mcas = self.mcasImpl
      override protected def rigInstance = this.mcasImpl.currentContext().refIdGen
    }.rxn[Int, Float, Double, Long])

    checkAll("Ref", RefLawTests(self).ref[String, Int])
    checkAll("Reactive", ReactiveLawTests[SyncIO].reactive[String, Int])
    checkAll("AsyncReactive", AsyncReactiveLawTests[IO].asyncReactive[String, Int])

    checkAll("Monad[Rxn]", MonadTests[Rxn].monad[Int, String, Int])
    checkAll("Unique[Rxn]", UniqueTests[Rxn].unique(using { (act: Rxn[Boolean]) =>
      Prop(act.unsafePerform(self.mcasImpl))
    }))
    checkAll("Semigroup[Rxn]", SemigroupTests[Rxn[Int]](using Rxn.choiceSemigroup).semigroup)
    checkAll("Monoid[Rxn]", MonoidTests[Rxn[Int]](using Rxn.monoidForDevTauriChoamCoreRxn).monoid)
    checkAll("Defer[Rxn]", DeferTests[Rxn].defer[Int])
    checkAll("Align[Rxn]", AlignTests[Rxn].align[Int, Float, Double, Long])
    checkAll("Clock[Rxn]", ClockTests[Rxn].clock(using { (act: Rxn[Boolean]) =>
      Prop(act.unsafePerform(self.mcasImpl))
    }))

    checkAll("Hash[Ref[Int]]", HashTests[Ref[Int]].hash)
    checkAll("Hash[TRef[Int]]", HashTests[TRef[Int]].hash)

    checkAll("Order[MemoryLocation[?]]", OrderTests[MemoryLocation[String]].order)

    checkAll("InvariantSemigroupal[RefLike]", InvariantSemigroupalTests[RefLike].invariantSemigroupal[String, Int, Long])
    checkAll("InvariantSemigroupal[Map]", InvariantSemigroupalTests[data.Map[String, *]].invariantSemigroupal[String, Int, Long])
    checkAll("Invariant[Map.Extra]", InvariantTests[data.Map.Extra[String, *]].invariant[String, Int, Long])
  }
}
