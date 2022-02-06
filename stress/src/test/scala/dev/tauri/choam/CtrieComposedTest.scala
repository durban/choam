/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.Hash

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import mcas.MCAS
import data.Ctrie

// @JCStressTest
@State
@Description("Composed Ctrie insert/lookup should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("(Some(0),Some(1)), (Some(x),Some(y))"), expect = ACCEPTABLE, desc = "get first"),
  new Outcome(id = Array("(Some(x),Some(y)), (Some(x),Some(y))"), expect = ACCEPTABLE, desc = "ins first")
))
class CtrieComposedTest extends StressTestBase {

  private[this] val ct1 =
    CtrieComposedTest.newCtrie714Small()

  private[this] val ct2 =
    CtrieComposedTest.newCtrie714Small()

  private[this] val insert: Rxn[((Int, String), (Int, String)), (Unit, Unit)] =
    ct1.insert × ct2.insert

  private[this] val lookup: Rxn[(Int, Int), (Option[String], Option[String])] =
    ct1.lookup × ct2.lookup

  @Actor
  def ins(): Unit = {
    insert.unsafePerform((14 -> "x", 1 -> "y"), this.impl)
    ()
  }

  @Actor
  def get(r: LL_Result): Unit = {
    r.r1 = lookup.unsafePerform((14, 1), this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = lookup.unsafePerform((14, 1), this.impl)
  }
}

object CtrieComposedTest {

  def newCtrie714Small(): Ctrie[Int, String] = {
    val h = new Hash[Int] {
      override def eqv(x: Int, y: Int): Boolean =
        x % 14 == y % 14
      override def hash(x: Int): Int =
        x % 7
    }
    val ct = Ctrie.unsafe[Int, String](h)
    ct.insert.unsafePerform(0 -> "0", MCAS.EMCAS)
    ct.insert.unsafePerform(1 -> "1", MCAS.EMCAS)
    ct.insert.unsafePerform(7 -> "7", MCAS.EMCAS)
    ct.insert.unsafePerform(8 -> "8", MCAS.EMCAS)
    ct
  }
}
