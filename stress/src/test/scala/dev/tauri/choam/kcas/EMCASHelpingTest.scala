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
import org.openjdk.jcstress.infra.results.ZLL_Result

import mcas.MemoryLocation

@JCStressTest
@State
@Description("EMCASHelpingTest")
@Outcomes(Array(
  new Outcome(id = Array("true, a, x"), expect = ACCEPTABLE, desc = "observed old values"),
  new Outcome(id = Array("true, b, x"), expect = ACCEPTABLE, desc = "observed new r1"),
  new Outcome(id = Array("true, a, y"), expect = ACCEPTABLE, desc = "observed new r2"),
  new Outcome(id = Array("true, b, y"), expect = ACCEPTABLE, desc = "observed new values")
))
class EMCASHelpingTest {

  private[this] val ref1 =
    Ref.unsafeWithId("a")(0L, 0L, 0L, i3 = 42L)

  private[this] val ref2 =
    Ref.unsafeWithId("x")(0L, 0L, 0L, i3 = 21L)

  assert(MemoryLocation.globalCompare(ref1, ref2) > 0) // ref1 > ref2

  @Actor
  def writer(r: ZLL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    val ok = EMCAS.tryPerform(
      EMCAS.addCas(
        EMCAS.addCas(EMCAS.start(ctx), this.ref1, "a", "b", ctx),
        this.ref2,
        "x",
        "y",
        ctx
      ),
      ctx
    )
    r.r1 = ok
  }

  @Actor
  def helper1(r: ZLL_Result): Unit = {
    r.r2 = EMCAS.read(this.ref1, EMCAS.currentContext())
  }

  @Actor
  def helper2(r: ZLL_Result): Unit = {
    r.r3 = EMCAS.read(this.ref2, EMCAS.currentContext())
  }

  @Arbiter
  def arbiter(): Unit = {
    val ctx = EMCAS.currentContext()
    assert(EMCAS.read(this.ref1, ctx) eq "b")
    assert(EMCAS.read(this.ref2, ctx) eq "y")
  }
}
