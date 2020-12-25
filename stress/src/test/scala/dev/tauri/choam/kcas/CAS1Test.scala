/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.ZZL_Result

@JCStressTest
@State
@Description("CAS1 should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, false, x"), expect = ACCEPTABLE, desc = "T1 succeeded"),
  new Outcome(id = Array("false, true, y"), expect = ACCEPTABLE, desc = "T2 succeeded")
))
class CAS1Test extends StressTestBase {

  private[this] val ref: Ref[String] =
    Ref.mk("ov")

  @Actor
  def writer1(r: ZZL_Result): Unit = {
    r.r1 = impl.tryPerform(impl.addCas(impl.start(), ref, "ov", "x"))
  }

  @Actor
  def writer2(r: ZZL_Result): Unit = {
    r.r2 = impl.tryPerform(impl.addCas(impl.start(), ref, "ov", "y"))
  }

  @Actor
  def reader(r: ZZL_Result): Unit = {
    r.r3 = impl.read(ref)
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    val fv = impl.read(ref)
    r.r3 match {
      case null =>
        throw new AssertionError(s"unexpected value: null")
      case "ov" =>
        // OK
      case nv if (nv eq fv) =>
        // OK
      case nv =>
        throw new AssertionError(s"unexpected value: ${nv}")
    }
    r.r3 = fv
  }
}
