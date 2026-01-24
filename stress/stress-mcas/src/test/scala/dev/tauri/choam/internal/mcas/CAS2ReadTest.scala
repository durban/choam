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
import org.openjdk.jcstress.infra.results.LLZ_Result

@JCStressTest
@State
@Description("CAS2 should be atomic to readers")
@Outcomes(Array(
  new Outcome(id = Array("ov1, ov2, true"), expect = ACCEPTABLE, desc = "Read old values"),
  new Outcome(id = Array("ov1, b, true"), expect = ACCEPTABLE_INTERESTING, desc = "Read old from ref1, new from ref2"),
  new Outcome(id = Array("a, ov2, true"), expect = FORBIDDEN, desc = "Read new from ref1, but old from ref2"),
  new Outcome(id = Array("a, b, true"), expect = ACCEPTABLE, desc = "Read new values")
))
class CAS2ReadTest extends StressTestBase {

  private[this] val ref1: MemoryLocation[String] =
    MemoryLocation.unsafePadded("ov1", impl.currentContext().refIdGen)

  private[this] val ref2: MemoryLocation[String] =
    MemoryLocation.unsafePadded("ov2", impl.currentContext().refIdGen)

  @Actor
  def writer(r: LLZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r3 = (ctx.tryPerformInternal(
      ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), ref1, "ov1", "a"), ref2, "ov2", "b"),
      Consts.PESSIMISTIC
    ) == McasStatus.Successful)
  }

  @Actor
  def reader(r: LLZ_Result): Unit = {
    val ctx = impl.currentContext()
    r.r1 = ctx.readDirect(ref1)
    r.r2 = ctx.readDirect(ref2)
  }

  @Arbiter
  def arbiter(r: LLZ_Result): Unit = {
    val ctx = impl.currentContext()
    val ok1 = (ctx.readDirect(this.ref1) eq "a")
    val ok2 = (ctx.readDirect(this.ref2) eq "b")
    if (!(ok1 && ok2)) {
      r.r3 = false
    }
  }
}
