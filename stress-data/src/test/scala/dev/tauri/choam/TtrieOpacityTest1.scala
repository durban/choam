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

@JCStressTest
@State
@Description("Ttrie lookup should be opaque (concurrent insertion)")
@Outcomes(Array(
  new Outcome(id = Array("None, (Some(a),Some(a)), (Some(a),Some(a))"), expect = ACCEPTABLE_INTERESTING, desc = "ins wins"),
  new Outcome(id = Array("None, (None,None), (Some(a),Some(a))"), expect = ACCEPTABLE, desc = "get wins")
))
class TtrieOpacityTest1 extends StressTestBase {

  private[this] final val key =
    0x128cd4

  private[this] val ttrie =
    TtrieTest.newRandomTtrie(size = 128, avoid = key)

  private[this] val insert: (Int, String) =#> Option[String] =
    ttrie.put

  private[this] val lookup: Int =#> (Option[String], Option[String]) =
    ttrie.get * ttrie.get

  private[this] val kv =
    key -> "a"

  @Actor
  def ins(r: LLL_Result): Unit = {
    r.r1 = insert.unsafePerform(kv, this.impl)
  }

  @Actor
  def get(r: LLL_Result): Unit = {
    r.r2 = lookup.unsafePerform(key, this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = lookup.unsafePerform(key, this.impl)
  }
}
