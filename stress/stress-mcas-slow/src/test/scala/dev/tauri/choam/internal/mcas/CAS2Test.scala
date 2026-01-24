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
package internal
package mcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZZZ_Result

@JCStressTest
@State
@Description("CAS2 should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, true, false, true"), expect = ACCEPTABLE, desc = "The two 1-CAS succeeded"),
  new Outcome(id = Array("true, false, false, .*"), expect = FORBIDDEN, desc = "writer2 failed and 2-CAS too"),
  new Outcome(id = Array("false, true, false, .*"), expect = FORBIDDEN, desc = "writer1 failed and 2-CAS too"),
  new Outcome(id = Array("false, false, true, true"), expect = ACCEPTABLE_INTERESTING, desc = "The 2-CAS succeeded")
))
class CAS2Test extends StressTestBase {

  private[this] val ref1: MemoryLocation[String] =
    MemoryLocation.unsafePadded("ov1", impl.currentContext().refIdGen)

  private[this] val ref2: MemoryLocation[String] =
    MemoryLocation.unsafePadded("ov2", impl.currentContext().refIdGen)

  @Actor
  def writer1(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r1 = (ctx.tryPerformInternal(
      ctx.addCasFromInitial(ctx.start(), ref1, "ov1", "x"),
      Consts.PESSIMISTIC
    ) == McasStatus.Successful)
  }

  @Actor
  def writer2(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r2 = (ctx.tryPerformInternal(
      ctx.addCasFromInitial(ctx.start(), ref2, "ov2", "y"),
      Consts.PESSIMISTIC
    ) == McasStatus.Successful)
  }

  @Actor
  def writer3(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r3 = (ctx.tryPerformInternal(
      ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), ref1, "ov1", "a"), ref2, "ov2", "b"),
      Consts.PESSIMISTIC
    ) == McasStatus.Successful)
  }

  @Arbiter
  def abriter(r: ZZZZ_Result): Unit = {
    val ctx = impl.currentContext()
    if (r.r3) {
      if ((ctx.readDirect(ref1) == "a") && (ctx.readDirect(ref2) == "b")) {
        r.r4 = true
      }
    } else if (r.r1 && r.r2) {
      if ((ctx.readDirect(ref1) == "x") && (ctx.readDirect(ref2) == "y")) {
        r.r4 = true
      }
    }
  }
}
