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
package internal
package mcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZL_Result

@JCStressTest
@State
@Description("CAS1 should be atomic to readers")
@Outcomes(Array(
  new Outcome(id = Array("true, true, ov"), expect = ACCEPTABLE, desc = "reader was faster"),
  new Outcome(id = Array("true, true, x"), expect = ACCEPTABLE_INTERESTING, desc = "writer was faster")
))
class CAS1ReadTest extends StressTestBase {

  private[this] val ref: MemoryLocation[String] =
    MemoryLocation.unsafe("ov")

  @Actor
  def writer(r: ZZL_Result): Unit = {
    val ctx = impl.currentContext()
    r.r1 = (ctx.tryPerformInternal(ctx.addCasFromInitial(ctx.start(), ref, "ov", "x")) == McasStatus.Successful)
  }

  @Actor
  def reader(r: ZZL_Result): Unit = {
    r.r3 = impl.currentContext().readDirect(ref)
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    val fv = impl.currentContext().readDirect(ref)
    r.r2 = (fv eq "x")
  }
}
