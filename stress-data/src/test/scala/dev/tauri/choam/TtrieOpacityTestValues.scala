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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

// This test demonstrates the problem
// Ttrie#values: multiple calls in one
// Rxn can be inconsistent.

// @JCStressTest
@State
@Description("ttrie values")
@Outcomes(Array(
  new Outcome(id = Array("None, (129,129), 129"), expect = ACCEPTABLE, desc = "insertion wins"),
  new Outcome(id = Array("None, (128,128), 129"), expect = ACCEPTABLE, desc = "values wins"),
  new Outcome(id = Array("None, (128,129), 129"), expect = FORBIDDEN, desc = "ERROR: inconsistent values"),
))
class TtrieOpacityTestValues extends StressTestBase {

  private[this] final val key =
    0xbc5747

  private[this] val ttrie =
    TtrieTest.newRandomTtrie(size = 128, avoid = key)

  private[this] val insert: (Int, String) =#> Option[String] =
    ttrie.put

  private[this] val values: Axn[(Vector[String], Vector[String])] =
    ttrie.values * ttrie.values

  private[this] val kv =
    key -> "a"

  @Actor
  def insertion(r: LLL_Result): Unit = {
    r.r1 = insert.unsafePerform(kv, this.impl)
  }

  @Actor
  def values(r: LLL_Result): Unit = {
    val vectors = values.unsafePerform(null, this.impl)
    r.r2 = (vectors._1.size, vectors._2.size)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = ttrie.values.unsafePerform(key, this.impl).size
  }
}
