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
package kcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZZZ_Result

import mcas.MemoryLocation

// @JCStressTest
@State
@Description("CAS2 should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, true, false, true"), expect = ACCEPTABLE, desc = "The two 1-CAS succeeded"),
  new Outcome(id = Array("true, false, false, .*"), expect = FORBIDDEN, desc = "writer2 failed and 2-CAS too"),
  new Outcome(id = Array("false, true, false, .*"), expect = FORBIDDEN, desc = "writer1 failed and 2-CAS too"),
  new Outcome(id = Array("false, false, true, true"), expect = ACCEPTABLE, desc = "The 2-CAS succeeded")
))
class CAS2Test extends StressTestBase {

  private[this] val ref1: MemoryLocation[String] =
    Ref.unsafe("ov1").loc

  private[this] val ref2: MemoryLocation[String] =
    Ref.unsafe("ov2").loc

  @Actor
  def writer1(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r1 = impl.tryPerform(
      impl.addCas(impl.start(ctx), ref1, "ov1", "x", ctx),
      ctx
    )
  }

  @Actor
  def writer2(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r2 = impl.tryPerform(
      impl.addCas(impl.start(ctx), ref2, "ov2", "y", ctx),
      ctx
    )
  }

  @Actor
  def writer3(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r3 = impl.tryPerform(
      impl.addCas(impl.addCas(impl.start(ctx), ref1, "ov1", "a", ctx), ref2, "ov2", "b", ctx),
      ctx
    )
  }

  @Arbiter
  def abriter(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    if (r.r3) {
      if ((impl.read(ref1, ctx) == "a") && (impl.read(ref2, ctx) == "b")) {
        r.r4 = true
      }
    } else if (r.r1 && r.r2) {
      if ((impl.read(ref1, ctx) == "x") && (impl.read(ref2, ctx) == "y")) {
        r.r4 = true
      }
    }
  }
}
