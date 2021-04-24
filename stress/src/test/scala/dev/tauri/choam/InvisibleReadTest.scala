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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLZ_Result

@JCStressTest
@State
@Description("Invisible read may see intermediate values, but not descriptors")
@Outcomes(Array(
  new Outcome(id = Array("a, x, true"), expect = ACCEPTABLE, desc = "Sees old values"),
  new Outcome(id = Array("b, x, true"), expect = ACCEPTABLE_INTERESTING, desc = "Sees new ref1"),
  new Outcome(id = Array("a, y, true"), expect = ACCEPTABLE_INTERESTING, desc = "Sees new ref2"),
  new Outcome(id = Array("b, y, true"), expect = ACCEPTABLE, desc = "Sees new values")
))
class InvisibleReadTest extends StressTestBase {

  private[this] val ref1: Ref[String] =
    Ref.unsafe("a")

  private[this] val ref2: Ref[String] =
    Ref.unsafe("x")

  private[this] val read1: Axn[String] =
    Rxn.unsafe.invisibleRead(ref1)

  private[this] val read2: Axn[String] =
    Rxn.unsafe.invisibleRead(ref2)

  private[this] val write =
    ref1.unsafeCas("a", "b") >>> ref2.unsafeCas("x", "y")

  @Actor
  def writer(@unused r: LLZ_Result): Unit = {
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
    val fv1 = impl.read(ref1, ctx)
    val fv2 = impl.read(ref2, ctx)
    if ((fv1 eq "b") && (fv2 eq "y")) {
      r.r3 = true
    }
  }
}
