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
import org.openjdk.jcstress.infra.results.ZL_Result

@JCStressTest
@State
@Description("k-CAS should be atomic to readers")
@Outcomes(Array(
  new Outcome(id = Array("true, Set(ov)"), expect = ACCEPTABLE, desc = "Read old values"),
  new Outcome(id = Array("true, Set(x)"), expect = ACCEPTABLE, desc = "Read new values"),
  new Outcome(id = Array("true, Set(ov, x)", "true, Set(x, ov)"), expect = ACCEPTABLE, desc = "Read both values")
))
class KCASReadTest extends StressTestBase {

  private[this] val refs: List[Ref[String]] =
    List.fill(8)(Ref.unsafe("ov"))

  @Actor
  def writer(r: ZL_Result): Unit = {
    val ctx = impl.currentContext()
    val d = refs.foldLeft(impl.start(ctx)) { (d, ref) =>
      impl.addCas(d, ref, "ov", "x", ctx)
    }
    r.r1 = impl.tryPerform(d, ctx)
  }

  @Actor
  def reader(r: ZL_Result): Unit = {
    val ctx = impl.currentContext()
    r.r2 = refs.map(ref => impl.read(ref, ctx)).toSet
  }
}
