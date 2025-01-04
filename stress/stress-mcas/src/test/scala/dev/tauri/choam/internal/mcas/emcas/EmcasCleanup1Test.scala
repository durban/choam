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
import org.openjdk.jcstress.infra.results.ILL_Result

// TODO: Cleanup never seems to happen,
// TODO: we should force GC somehow.
// @JCStressTest
@State
@Description("EmcasCleanup1Test")
@Outcomes(Array(
  new Outcome(id = Array("1, EmcasWordDesc\\(a, b\\), ACTIVE"), expect = ACCEPTABLE, desc = "(1) has desc, active op"),
  new Outcome(id = Array("1, EmcasWordDesc\\(a, b\\), SUCCESSFUL"), expect = ACCEPTABLE, desc = "(2) has desc, finalized op"),
  new Outcome(id = Array("1, b, -"), expect = ACCEPTABLE_INTERESTING, desc = "(3) final value, desc was cleaned up")
))
class EmcasCleanup1Test {

  private[this] val ref =
    MemoryLocation.unsafe("a")

  @Actor
  final def write(r: ILL_Result): Unit = {
    val ctx = Emcas.inst.currentContext()
    val res = ctx.tryPerformInternal(ctx.addCasFromInitial(ctx.start(), this.ref, "a", "b"), Consts.PESSIMISTIC)
    r.r1 = if (res == McasStatus.Successful) 1 else -1
  }

  @Actor
  @tailrec
  final def read(r: ILL_Result): Unit = {
    (this.ref.unsafeGetV() : Any) match {
      case s: String if s eq "a" =>
        // no CAS yet, retry:
        read(r)
      case wd: EmcasWordDesc[_] =>
        // observing the descriptor:
        r.r2 = wd
        r.r3 = wd.parent.getStatusV()
      case x =>
        // was cleaned up, observing final value:
        r.r2 = x
        r.r3 = "-"
    }
  }

  @Arbiter
  final def arbiter(r: ILL_Result): Unit = {
    // EmcasWordDesc is not serializable:
    r.r2 match {
      case wd: EmcasWordDesc[_] =>
        // we ignore address here, it just generates a lot of output
        r.r2 = s"EmcasWordDesc(${wd.ov}, ${wd.nv})"
      case _ =>
        ()
    }
    r.r3 match {
      case v: Long =>
        r.r3 = v match {
          case v if EmcasStatus.isSuccessful(v) => "SUCCESSFUL"
          case McasStatus.Active => "ACTIVE"
          case McasStatus.Successful => "error: McasStatus.Successful"
          case McasStatus.FailedVal => "FAILED"
          case Version.None => "error: Version.None"
          case x => s"error: ${x}L"
        }
      case _ =>
        ()
    }
  }
}
