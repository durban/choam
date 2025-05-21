/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.LLLL_Result

import core.=#>

@JCStressTest
@State
@Description("Ttrie lookup should be opaque (concurrent insertion)")
@Outcomes(Array(
  new Outcome(id = Array("None, (Some(a),Some(a)), (Some(a),Some(a)), null"), expect = ACCEPTABLE, desc = "ins wins"),
  new Outcome(id = Array("None, (None,None), (Some(a),Some(a)), null"), expect = ACCEPTABLE_INTERESTING, desc = "get wins")
))
class TtrieOpacityTest1 extends StressTestBase {

  private[this] final val key =
    0x128cd4

  private[this] val ttrie =
    TtrieTest.newRandomTtrie(size = 128, avoid = key)

  private[this] val insert: (Int, String) =#> Option[String] =
    ttrie.put

  private[this] final def lookup(r: LLLL_Result): Int =#> (Option[String], Option[String]) = {
    (ttrie.get * ttrie.get).map { optopt =>
      if (optopt._1 != optopt._2) {
        if (r ne null) {
          r.r4 = optopt
        }
      }
      optopt
    }
  }

  private[this] val kv =
    key -> "a"

  @Actor
  def ins(r: LLLL_Result): Unit = {
    r.r1 = insert.unsafePerform(kv, this.impl)
  }

  @Actor
  def get(r: LLLL_Result): Unit = {
    r.r2 = lookup(r).unsafePerform(key, this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r3 = lookup(null).unsafePerform(key, this.impl)
  }
}
