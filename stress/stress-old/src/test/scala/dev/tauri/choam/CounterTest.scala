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
import org.openjdk.jcstress.infra.results.JJJ_Result

import data.CounterHelper

// @JCStressTest
@State
@Description("Counter incr/decr/count should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("0, 1, 0"), expect = ACCEPTABLE, desc = "incr is first"),
  new Outcome(id = Array("-1, 0, 0"), expect = ACCEPTABLE, desc = "decr is first")
))
class CounterTest extends StressTestBase {

  private[this] val ctr =
    CounterHelper.unsafe()

  private[this] val incr =
    ctr.incr

  private[this] val decr =
    ctr.decr

  private[this] val count =
    ctr.count

  @Actor
  def increment(r: JJJ_Result): Unit = {
    r.r1 = incr.unsafePerform((), this.impl)
  }

  @Actor
  def decrement(r: JJJ_Result): Unit = {
    r.r2 = decr.unsafePerform((), this.impl)
  }

  @Arbiter
  def arbiter(r: JJJ_Result): Unit = {
    r.r3 = count.unsafePerform((), this.impl)
  }
}
