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

import java.util.concurrent.CountDownLatch

import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.infra.results._

@JCStressTest
@State
@Description("IBR reservation should forbid freeing an object")
@Outcomes(Array(
  new Outcome(id = Array("true, null"), expect = ACCEPTABLE_INTERESTING, desc = "ok, still holds it"),
  new Outcome(id = Array("true, begin"), expect = ACCEPTABLE, desc = "check was too quick")
))
class IBRReservationTest {

  private[this] val ref =
    Ref.unsafe("begin")

  private[this] val latch =
    new CountDownLatch(1)

  // Note: due to the `latch`, the methods
  // below need to be in this exact order,
  // otherwise jcstress deadlocks during the
  // sanity check.

  @Actor
  def check(r: ZL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    this.ref.asInstanceOf[Ref[Any]].unsafeGetVolatile() match {
      case wd: WordDescriptor[_] =>
        r.r1 = ctx.isInUseByOther(wd)
      case s: String =>
        r.r1 = (s == "begin")
        r.r2 = s
      case x =>
        r.r2 = s"unexpected value: ${x}"
    }
    latch.countDown()
  }

  @Actor
  def block(@unused r: ZL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    ctx.startOp()
    val d = WordDescriptor(null, "", "", null, ctx) // allocate
    assert(ctx.casRef(this.ref.asInstanceOf[Ref[Any]], "begin", d)) // publish
    latch.await()
    ctx.endOp()
  }
}
