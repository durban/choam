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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

// @JCStressTest
@State
@Description("DelayComputed prepare should be a separate reaction")
@Outcomes(Array(
  new Outcome(id = Array("(ab,x), (a,x), (abc,xy)"), expect = ACCEPTABLE, desc = "Reader runs first"),
  new Outcome(id = Array("(ab,x), (ab,x), (abc,xy)"), expect = ACCEPTABLE_INTERESTING, desc = "Reader runs between prepare and reaction"),
  new Outcome(id = Array("(ab,x), (abc,xy), (abc,xy)"), expect = ACCEPTABLE, desc = "Reader runs last")
))
class DelayComputedTest extends StressTestBase {

  private[this] val ref1: Ref[String] =
    Ref.unsafe("a")

  private[this] val ref2: Ref[String] =
    Ref.unsafe("x")

  private[this] val composed: Axn[(String, String)] = {
    val dComp = Rxn.unsafe.delayComputed(ref1.unsafeDirectRead.flatMap { v1 =>
      ref1.unsafeCas(v1, v1 + "b").map { _ => // this modify runs during "prepare"
        ref1.getAndUpdate(_ + "c") // this modify is part of the final reaction
      }
    })
    // this is also part of the final reaction:
    val other = {
      ref2.unsafeCas("invalid", "q").map {_ => "q" } + // this will fail
        ref2.getAndUpdate(_ + "y") // but this will succeed
    }
    (dComp * other)
  }

  private[this] val read: Axn[(String, String)] =
    Rxn.consistentRead(ref2, ref1).map { case (r2, r1) => (r1, r2) }

  @Actor
  def writer(r: LLL_Result): Unit = {
    r.r1 = composed.unsafePerform((), this.impl)
  }

  @Actor
  def reader(r: LLL_Result): Unit = {
    r.r2 = read.unsafePerform((), this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    val ctx = this.impl.currentContext()
    r.r3 = (ctx.readDirect(ref1.loc), ctx.readDirect(ref2.loc))
  }
}
