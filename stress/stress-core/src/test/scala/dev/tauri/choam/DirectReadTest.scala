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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLZ_Result

import core.{ Rxn, Axn, Ref }

@JCStressTest
@State
@Description("Direct read may see intermediate values, but not descriptors")
@Outcomes(Array(
  new Outcome(id = Array("a, x, true"), expect = ACCEPTABLE, desc = "Sees old values"),
  new Outcome(id = Array("a, y, true"), expect = ACCEPTABLE_INTERESTING, desc = "Sees new ref2 only"),
  new Outcome(id = Array("b, y, true"), expect = ACCEPTABLE, desc = "Sees new values"),
  // The reads in `reader` are ordered, so this mustn't happen:
  new Outcome(id = Array("b, x, true"), expect = FORBIDDEN, desc = "Sees new ref1 only"),
))
class DirectReadTest extends StressTestBase {

  private[this] val ref1: Ref[String] =
    Ref.unsafePadded("a", this.rig)

  private[this] val ref2: Ref[String] =
    Ref.unsafePadded("x", this.rig)

  private[this] val read1: Axn[String] =
    Rxn.unsafe.directRead(ref1)

  private[this] val read2: Axn[String] =
    Rxn.unsafe.directRead(ref2)

  private[this] val write =
    ref1.update(_ => "b") >>> ref2.update(_ => "y")

  @Actor
  def writer(): Unit = {
    write.unsafeRun(this.impl)
  }

  @Actor
  def reader(r: LLZ_Result): Unit = {
    r.r1 = read1.unsafeRun(this.impl)
    r.r2 = read2.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LLZ_Result): Unit = {
    val ctx = impl.currentContext()
    val fv1 = ctx.readDirect(ref1.loc)
    val fv2 = ctx.readDirect(ref2.loc)
    if ((fv1 eq "b") && (fv2 eq "y")) {
      r.r3 = true
    }
  }
}
