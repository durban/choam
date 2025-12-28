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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

import core.Ref

@JCStressTest
@State
@Description("SparseRefArray should be safely initialized")
@Outcomes(Array(
  new Outcome(id = Array("0, 0, x"), expect = ACCEPTABLE, desc = "read first"),
  new Outcome(id = Array("1, x, y"), expect = ACCEPTABLE, desc = "read found it"),
))
class RefArrayLazyInitTest extends StressTestBase {

  private[this] val f: Function1[String, String] =
    { _ => "y" }

  // intentionally not volatile
  private[this] var arr: Ref.Array[String] =
    null

  @Actor
  def write(): Unit = {
    this.arr = Ref.unsafeArray[String](4, "x", Ref.Array.AllocationStrategy.SparseFlat, this.rig)
  }

  @Actor
  def read(r: LLL_Result): Unit = {
    this.arr match {
      case null =>
        // we're too early
        r.r1 = "0"
        r.r2 = "0"
      case a =>
        // we found something, so it
        // should work properly:
        r.r2 = a.getOrCreateRefOrNull(2).getAndUpdate(f).unsafePerform(this.impl)
        r.r1 = "1"
    }
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = this.arr.getOrCreateRefOrNull(2).get.unsafePerform(this.impl)
  }
}
