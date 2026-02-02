/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.LL_Result

import core.{ Rxn, Ref }

@JCStressTest
@State
@Description("Defer#fix acq/rel fences are necessary")
@Outcomes(Array(
  new Outcome(id = Array("null, null"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
  new Outcome(id = Array("res, foo"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class FixSync extends StressTestBase {

  private[this] val ctr: Ref[Int] =
    Ref[Int](0).unsafePerform(this.impl)

  private[this] val incrCtr: Rxn[Int] =
    ctr.getAndUpdate(_ + 1)

  private[this] var holder: Rxn[String] =
    null

  @Actor
  def writer(): Unit = {
    val rxn = Rxn.deferForDevTauriChoamCoreRxn.fix[String] { rec => // Note: if instead of this line,
    // we do this:
    //   val rxn = Rxn.deferFixWithoutFences[String] { rec =>
    // then this test fails on ARM Linux, because a `null`
    // can be read from `ref.elem` in `fix`. This demonstrates
    // that the rel/acq fences in `fix` are really necessary.
      this.incrCtr.flatMap { c =>
        if (c > 0) Rxn.pure("foo")
        else rec
      }
    }
    this.holder = rxn // plain write
  }

  @Actor
  def reader(r: LL_Result): Unit = {
    val rxn = this.holder // plain read
    if (rxn eq null) {
      () // (null, null)
    } else {
      r.r2 = rxn.unsafePerform(this.impl)
      r.r1 = "res"
    }
  }
}
