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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLL_Result

// @JCStressTest
@State
@Description("LazyRefArray Refs should be safely created")
@Outcomes(Array(
  new Outcome(id = Array("ok, ok, x, x"), expect = ACCEPTABLE, desc = "correct lazy init"),
))
class RefArrayLazyRaceTest extends StressTestBase {

  private[this] final val arr: Ref.Array[String] = {
    val a = Ref.unsafeLazyArray[String](4, "x")
    a.unsafeGet(0).loc.unsafeSetVolatile("-")
    a.unsafeGet(1).loc.unsafeSetVolatile("-")
    // we don't change/initialize `a(2)`
    a.unsafeGet(3).loc.unsafeSetVolatile("-")
    a
  }

  @Actor
  def one(r: LLLL_Result): Unit = {
    val ref = this.arr.unsafeGet(2)
    val value = ref.loc.unsafeGetVolatile()
    r.r1 = ref
    r.r3 = value
  }

  @Actor
  def two(r: LLLL_Result): Unit = {
    val ref = this.arr.unsafeGet(2)
    val value = ref.loc.unsafeGetVolatile()
    r.r2 = ref
    r.r4 = value
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    if (r.r1 eq r.r2) {
      // OK, the 2 refs are the same
      r.r1 = "ok"
      r.r2 = "ok"
    } else {
      // ERR, different refs
      r.r1 = r.r1.toString()
      r.r2 = r.r2.toString()
    }
  }
}
