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
import org.openjdk.jcstress.infra.results.LLLLL_Result

@JCStressTest
@State
@Description("EMCASTest")
@Outcomes(Array(
// scalastyle:off
  new Outcome(id = Array("true, 21, 42, ACTIVE, null"), expect = ACCEPTABLE, desc = "observed descriptors in correct  order (active)"),
  new Outcome(id = Array("true, 21, 42, SUCCESSFUL, null"), expect = ACCEPTABLE, desc = "observed descriptors in correct  order (finalized)"),
  new Outcome(id = Array("true, 21, 42, FAILED, null"), expect = FORBIDDEN, desc = "observed descriptors in correct  order, but failed status"),
  new Outcome(id = Array("true, 42, 21, ACTIVE, null", "true, 42, 21, SUCCESSFUL, null"), expect = FORBIDDEN, desc = "observed descriptors in incorrect (unsorted) order"),
  new Outcome(id = Array("true, -1, -1, y, null"), expect = ACCEPTABLE_INTERESTING, desc = "descriptor was already cleaned up")
// scalastyle:on
))
class EMCASTest {

  private[this] val ref1 =
    Ref.mkWithId("a")(0L, 0L, 0L, i3 = 42L)

  private[this] val ref2 =
    Ref.mkWithId("x")(0L, 0L, 0L, i3 = 21L)

  assert(Ref.globalCompare(ref1, ref2) > 0) // ref1 > ref2

  // LLLLL_Result:
  // r1: k-CAS result (Boolean)
  // r2: `id3` of first observed descriptor (Long)
  // r3: `id3` of second observed descriptor (Long)
  // r4: `status` of observed parent (StatusType) OR final object
  // r5: any unexpected object (for debugging)

  @Actor
  def write(r: LLLLL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    val ok = EMCAS.tryPerform(
      EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), this.ref1, "a", "b", ctx), this.ref2, "x", "y", ctx),
      ctx
    )
    r.r1 = ok // true
  }

  @Actor
  def read(r: LLLLL_Result): Unit = {
    @tailrec
    def go(): Unit = {
      // ref2 will be acquired first:
      (this.ref2.unsafeTryRead() : Any) match {
        case s: String if s eq "x" =>
          go() // retry
        case d: WordDescriptor[_] =>
          val it = d.parent.words.iterator()
          val dFirst = it.next()
          val dSecond = it.next()
          r.r4 = d.parent.getStatus()
          r.r2 = dFirst.address.id3
          r.r3 = dSecond.address.id3
          if (it.hasNext) {
            // mustn't happen
            r.r5 = s"unexpected 3rd descriptor: ${it.next().toString}"
          }
        case s =>
          // mustn't happen
          r.r5 = s"unexpected object: ${s.toString}"
      }
    }
    go()
  }

  @Arbiter
  def arbiter(): Unit = {
    val ctx = EMCAS.currentContext()
    assert(EMCAS.read(this.ref1, ctx) eq "b")
    assert(EMCAS.read(this.ref2, ctx) eq "y")
  }
}
