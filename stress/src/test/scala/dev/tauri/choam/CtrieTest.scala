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

import cats.Hash

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LZ_Result

import kcas.KCAS
import data.Ctrie

@JCStressTest
@State
@Description("Ctrie insert/lookup should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("Some(x), true"), expect = ACCEPTABLE, desc = "ins2 wins"),
  new Outcome(id = Array("Some(y), true"), expect = ACCEPTABLE, desc = "ins1 wins")
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
    insert.unsafePerform(14 -> "x", this.impl)
  }

  @Actor
  def ins2(): Unit = {
    insert.unsafePerform(0 -> "y", this.impl)
  }

  @Arbiter
  def arbiter(r: LZ_Result): Unit = {
    r.r1 = lookup.unsafePerform(0, this.impl)
    r.r2 = (
      (lookup.unsafePerform(1, this.impl).get eq "1") &&
      (lookup.unsafePerform(2, this.impl).get eq "2") &&
      (lookup.unsafePerform(3, this.impl).get eq "3") &&
      (lookup.unsafePerform(4, this.impl).get eq "4") &&
      (lookup.unsafePerform(7, this.impl).get eq "7") &&
      (lookup.unsafePerform(8, this.impl).get eq "8") &&
      (lookup.unsafePerform(9, this.impl).get eq "9")
    )
  }
}

object CtrieTest {

  def newCtrie714(): Ctrie[Int, String] = {
    val h = new Hash[Int] {
      override def eqv(x: Int, y: Int): Boolean =
        x % 14 == y % 14
      override def hash(x: Int): Int =
        x % 7
    }
    val ct = Ctrie.unsafe[Int, String](h)
    ct.insert.unsafePerform(0 -> "0", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(1 -> "1", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(2 -> "2", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(3 -> "3", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(4 -> "4", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(7 -> "7", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(8 -> "8", KCAS.NaiveKCAS)
    ct.insert.unsafePerform(9 -> "9", KCAS.NaiveKCAS)
    ct
  }
}
