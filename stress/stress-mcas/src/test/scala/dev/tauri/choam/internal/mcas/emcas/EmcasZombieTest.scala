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
package internal
package mcas
package emcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLLL_Result

@JCStressTest
@State
@Description("EmcasZombieTest")
@Outcomes(Array(
  new Outcome(id = Array("Some(a), Some(b), true, true, x, y"), expect = ACCEPTABLE, desc = "Read before commit"),
  new Outcome(id = Array("Some(a), None, null, true, x, y"), expect = ACCEPTABLE_INTERESTING, desc = "OK, we detected the inconsistency"),
  new Outcome(id = Array("Some(x), Some(y), true, true, x, y"), expect = ACCEPTABLE, desc = "Read after commit"),
))
class EmcasZombieTest {

  private[this] val inst =
    StressTestBase.emcasInst

  private[this] val ref1 =
    MemoryLocation.unsafePadded("a", inst.currentContext().refIdGen) // -> x

  private[this] val ref2 =
    MemoryLocation.unsafePadded("b", inst.currentContext().refIdGen) // -> y

  @Actor
  def write(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    val Some((_, d1)) = ctx.readMaybeFromLog(ref1, d0, canExtend = true) : @unchecked
    val d2 = d1.overwrite(d1.getOrElseNull(ref1).withNv("x"))
    val Some((_, d3)) = ctx.readMaybeFromLog(ref2, d2, canExtend = true) : @unchecked
    val d4 = d3.overwrite(d3.getOrElseNull(ref2).withNv("y"))
    val ok = (ctx.tryPerform(d4) == McasStatus.Successful)
    r.r4 = ok // must be true
  }

  @Actor
  def read(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    ctx.readMaybeFromLog(ref1, d0, canExtend = true) match {
      case None =>
        // this mustn't happen (the log is empty)
        r.r1 = None
      case Some((ov1, d1)) =>
        r.r1 = Some(ov1)
        ctx.readMaybeFromLog(ref2, d1, canExtend = true) match {
          case None =>
            // OK, we detected the inconsistency
            r.r2 = None
          case Some((ov2, d2)) =>
            r.r2 = Some(ov2)
            val ok = (ctx.tryPerform(d2) == McasStatus.Successful)
            r.r3 = ok // must be true
        }
    }
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    r.r5 = ctx.readDirect(ref1)
    r.r6 = ctx.readDirect(ref2)
  }
}
