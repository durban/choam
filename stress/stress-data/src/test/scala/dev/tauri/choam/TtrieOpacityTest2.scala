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

import core.Rxn

@JCStressTest
@State
@Description("Ttrie lookup should be opaque (concurrent deletion)")
@Outcomes(Array(
  new Outcome(id = Array("true, (None,None), (None,None), null"), expect = ACCEPTABLE, desc = "del wins"),
  new Outcome(id = Array("true, (Some(a),Some(a)), (None,None), null"), expect = ACCEPTABLE_INTERESTING, desc = "get wins")
))
class TtrieOpacityTest2 extends StressTestBase {

  private[this] final val key =
    0x5cf0ff

  private[this] val ttrie = {
    val m = TtrieTest.newRandomTtrie(size = 128, avoid = key)
    m.put(key, "a").unsafePerform(this.impl)
    m
  }

  private[this] final def delete(k: Int): Rxn[Boolean] =
    ttrie.del(k)

  private[this] final def lookup(r: LLLL_Result, k: Int): Rxn[(Option[String], Option[String])] = {
    (ttrie.get(k) * ttrie.get(k)).map { optopt =>
      if (optopt._1 != optopt._2) {
        if (r ne null) {
          r.r4 = optopt
        }
      }
      optopt
    }
  }

  @Actor
  def del(r: LLLL_Result): Unit = {
    r.r1 = delete(key).unsafePerform(this.impl)
  }

  @Actor
  def get(r: LLLL_Result): Unit = {
    r.r2 = lookup(r, key).unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r3 = lookup(null, key).unsafePerform(this.impl)
  }
}
