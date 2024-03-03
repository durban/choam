/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.LLLLL_Result

@JCStressTest
@State
@Description("SparseRefArray Refs should be safely created")
@Outcomes(Array(
  new Outcome(id = Array("ok, ok, x, y1, y2"), expect = ACCEPTABLE, desc = "upd1 won"),
  new Outcome(id = Array("ok, ok, y2, x, y1"), expect = ACCEPTABLE, desc = "upd2 won"),
))
class RefArrayLazyRaceTest extends StressTestBase {

  private[this] val f1: Function1[String, String] =
    { _ => "y1" }

  private[this] val f2: Function1[String, String] =
    { _ => "y2" }

  private[this] final val arr: Ref.Array[String] =
    Ref.unsafeArray[String](4, "x", Ref.Array.AllocationStrategy.SparseFlat)

  @Actor
  def upd1(r: LLLLL_Result): Unit = {
    val ref = this.arr.unsafeGet(2)
    val value = ref.getAndUpdate(f1).unsafeRun(this.impl)
    r.r1 = ref
    r.r3 = value
  }

  @Actor
  def upd2(r: LLLLL_Result): Unit = {
    val ref = this.arr.unsafeGet(2)
    val value = ref.getAndUpdate(f2).unsafeRun(this.impl)
    r.r2 = ref
    r.r4 = value
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    if (r.r1 eq r.r2) {
      // OK, the 2 refs are the same
      r.r1 = "ok"
      r.r2 = "ok"
    } else {
      // ERR, different refs
      r.r1 = r.r1.toString()
      r.r2 = r.r2.toString()
    }
    r.r5 = this.arr.unsafeGet(2).get.unsafeRun(this.impl)
  }
}
