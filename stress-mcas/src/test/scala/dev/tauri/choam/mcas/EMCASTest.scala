/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLL_Result

@JCStressTest
@State
@Description("EMCASTest")
@Outcomes(Array(
  new Outcome(id = Array("true, 21, 42, ACTIVE, null"), expect = ACCEPTABLE, desc = "observed descriptors in correct  order (active)"),
  new Outcome(id = Array("true, 21, 42, SUCCESSFUL, null"), expect = ACCEPTABLE, desc = "observed descriptors in correct  order (finalized)"),
  new Outcome(id = Array("true, null, null, y, null"), expect = ACCEPTABLE_INTERESTING, desc = "descriptor was already cleaned up"),
  new Outcome(id = Array("true, 21, CL, SUCCESSFUL, null"), expect = ACCEPTABLE_INTERESTING, desc = "descriptor is being cleaned up right now (1)"),
  new Outcome(id = Array("true, CL, 42, SUCCESSFUL, null"), expect = ACCEPTABLE_INTERESTING, desc = "descriptor is being cleaned up right now (2)"),
  new Outcome(id = Array("true, CL, CL, SUCCESSFUL, null"), expect = ACCEPTABLE_INTERESTING, desc = "descriptor is being cleaned up right now (3)"),
  new Outcome(id = Array("true, 21, 42, FAILED, null"), expect = FORBIDDEN, desc = "observed descriptors in correct  order, but failed status"),
  new Outcome(id = Array("true, 42, 21, ACTIVE, null", "true, 42, 21, SUCCESSFUL, null"), expect = FORBIDDEN, desc = "observed descriptors in incorrect (unsorted) order")
))
class EMCASTest {

  private[this] val ref1 =
    MemoryLocation.unsafeWithId("a")(0L, 0L, 0L, i3 = 42L)

  private[this] val ref2 =
    MemoryLocation.unsafeWithId("x")(0L, 0L, 0L, i3 = 21L)

  assert(MemoryLocation.globalCompare(ref1, ref2) > 0) // ref1 > ref2

  // LLLLL_Result:
  // r1: k-CAS result (Boolean)
  // r2: `id3` of first observed descriptor (Long)
  // r3: `id3` of second observed descriptor (Long)
  // r4: `status` of observed parent OR final object
  // r5: any unexpected object (for debugging)

  @Actor
  def write(r: LLLLL_Result): Unit = {
    val ctx = Emcas.currentContext()
    val res = ctx.tryPerformInternal(
      ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), this.ref1, "a", "b"), this.ref2, "x", "y")
    )
    r.r1 = (res == EmcasStatus.Successful) // true
  }

  @Actor
  def read(r: LLLLL_Result): Unit = {
    @tailrec
    def go(): Unit = {
      // ref2 will be acquired first:
      (this.ref2.unsafeGetVolatile() : Any) match {
        case s: String if s eq "x" =>
          go() // retry
        case d: WordDescriptor[_] =>
          checkWd(d, r)
        case s: String if s eq "y" =>
          // descriptor was already cleaned up
          r.r4 = "y"
        case s =>
          // mustn't happen
          r.r5 = s"unexpected object: ${s.toString}"
      }
    }
    go()
  }

  private[this] def checkWd(d: WordDescriptor[_], r: LLLLL_Result): Unit = {
    val it = d.parent.wordIterator()
    val dFirst = it.next()
    val dSecond = it.next()
    r.r2 = if (dFirst ne null) {
      if (dFirst.address ne ref2) {
        // mustn't happen
        r.r5 = s"unexpected dFirst.address: ${dFirst.address}"
      }
      dFirst.address.id3
    } else {
      // in the process of clearing
      "CL"
    }
    r.r3 = if (dSecond ne null) {
      dSecond.address.id3
    } else {
      // in the process of clearing
      "CL"
    }
    r.r4 = d.parent.getStatus()
    if (it.hasNext) {
      // mustn't happen
      r.r5 = s"unexpected 3rd descriptor: ${it.next().toString}"
    }
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    val ctx = Emcas.currentContext()
    assert(ctx.readDirect(this.ref1) eq "b")
    assert(ctx.readDirect(this.ref2) eq "y")
    r.r4 match {
      case v: Long =>
        r.r4 = v match {
          case Version.Active => "ACTIVE"
          case Version.Successful => "SUCCESSFUL"
          case Version.FailedVal => "FAILED"
          case Version.None => "error"
          case _ => "FAILED"
        }
      case _ =>
        ()
    }
  }
}
