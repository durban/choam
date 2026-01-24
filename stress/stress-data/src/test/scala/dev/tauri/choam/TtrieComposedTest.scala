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

import cats.Hash

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

import core.Rxn
import internal.mcas.Mcas
import data.Map

@JCStressTest
@State
@Description("Composed Ttrie insert/lookup should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("(Some(0),Some(1)), (Some(0),Some(1)), (Some(x),Some(y))"), expect = ACCEPTABLE, desc = "get first"),
  new Outcome(id = Array("(Some(0),Some(1)), (Some(x),Some(y)), (Some(x),Some(y))"), expect = ACCEPTABLE_INTERESTING, desc = "ins first")
))
class TtrieComposedTest extends StressTestBase {

  private[this] val ct1 =
    TtrieComposedTest.newTtrie714Small()

  private[this] val ct2 =
    TtrieComposedTest.newTtrie714Small()

  private[this] final def insert(k1: Int, v1: String, k2: Int, v2: String): Rxn[(Option[String], Option[String])] =
    ct1.put(k1, v1) * ct2.put(k2, v2)

  private[this] final def lookup(k1: Int, k2: Int): Rxn[(Option[String], Option[String])] =
    ct1.get(k1) * ct2.get(k2)

  @Actor
  def ins(r: LLL_Result): Unit = {
    r.r1 = insert(14, "x", 1, "y").unsafePerform(this.impl)
  }

  @Actor
  def get(r: LLL_Result): Unit = {
    r.r2 = lookup(14, 1).unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = lookup(14, 1).unsafePerform(this.impl)
  }
}

object TtrieComposedTest {

  private[this] final def initMcas: Mcas =
    StressTestBase.emcasInst

  private final def newTtrie714Small(): Map[Int, String] = {
    val h = new Hash[Int] {
      override def eqv(x: Int, y: Int): Boolean =
        x % 14 == y % 14
      override def hash(x: Int): Int =
        x % 7
    }
    val m = Map.ttrie[Int, String](AllocationStrategy.Unpadded)(using h).unsafePerform(initMcas)
    m.put(0, "0").unsafePerform(initMcas)
    m.put(1, "1").unsafePerform(initMcas)
    m.put(7, "7").unsafePerform(initMcas)
    m.put(8, "8").unsafePerform(initMcas)
    m
  }
}
