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

import cats.Eq

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import kcas.KCAS

@JCStressTest
@State
@Description("Ctrie insert/lookup should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("Some(0), Some(x)", "Some(0), Some(y)"), expect = ACCEPTABLE, desc = "get first"),
  new Outcome(id = Array("Some(x), Some(y)"), expect = ACCEPTABLE, desc = "ins1, get, ins2"),
  new Outcome(id = Array("Some(y), Some(x)"), expect = ACCEPTABLE, desc = "ins2, get, ins1"),
  new Outcome(id = Array("Some(x), Some(x)", "Some(y), Some(y)"), expect = ACCEPTABLE, desc = "get last")
))
class CtrieTest extends StressTestBase {

  private[this] val ctrie =
    CtrieTest.newCtrie714()

  private[this] val insert =
    ctrie.insert

  private[this] val lookup =
    ctrie.lookup

  @Actor
  def ins1(): Unit = {
    insert.unsafePerform(14 -> "x")
  }

  @Actor
  def ins2(): Unit = {
    insert.unsafePerform(14 -> "y")
  }

  @Actor
  def get(r: LL_Result): Unit = {
    r.r1 = lookup.unsafePerform(0)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = lookup.unsafePerform(0)
    assert(lookup.unsafePerform(1).get eq "1")
    assert(lookup.unsafePerform(2).get eq "2")
    assert(lookup.unsafePerform(3).get eq "3")
    assert(lookup.unsafePerform(4).get eq "4")
    assert(lookup.unsafePerform(7).get eq "7")
    assert(lookup.unsafePerform(8).get eq "8")
    assert(lookup.unsafePerform(9).get eq "9")
  }
}

object CtrieTest {

  def newCtrie714(): Ctrie[Int, String] = {
    val ct = new Ctrie[Int, String](_ % 7, Eq.instance(_ % 14 == _ % 14))
    ct.insert.unsafePerform(0 -> "0")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(1 -> "1")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(2 -> "2")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(3 -> "3")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(4 -> "4")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(7 -> "7")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(8 -> "8")(KCAS.NaiveKCAS)
    ct.insert.unsafePerform(9 -> "9")(KCAS.NaiveKCAS)
    ct
  }
}
