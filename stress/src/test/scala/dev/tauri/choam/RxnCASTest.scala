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
import org.openjdk.jcstress.infra.results.ZZL_Result

@JCStressTest
@State
@Description("Rxn.unsafe.cas should be a simple CAS")
@Outcomes(Array(
  new Outcome(id = Array("true, false, x"), expect = ACCEPTABLE, desc = "T1 succeeded"),
  new Outcome(id = Array("false, true, y"), expect = ACCEPTABLE, desc = "T2 succeeded")
))
class RxnCASTest extends StressTestBase {

  private[this] val ref: Ref[String] =
    Ref.unsafe("ov")

  private[this] val cas =
    Rxn.computed { (nv: String) => Rxn.unsafe.cas(ref, "ov", nv) }.?

  @Actor
  def writer1(r: ZZL_Result): Unit = {
    r.r1 = cas.unsafePerform("x", this.impl).isDefined
  }

  @Actor
  def writer2(r: ZZL_Result): Unit = {
    r.r2 = cas.unsafePerform("y", this.impl).isDefined
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    r.r3 = impl.read(ref, impl.currentContext())
  }
}
