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
@Description("k-CAS should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, false, x"), expect = ACCEPTABLE, desc = "T1 succeeded"),
  new Outcome(id = Array("false, true, y"), expect = ACCEPTABLE, desc = "T2 succeeded")
))
class KCASTest extends StressTestBase {

  private[this] val refs: List[Ref[String]] =
    List.fill(8)(Ref.mk("ov"))

  private def write(nv: String): Boolean = {
    val ctx = impl.currentContext()
    val d = refs.foldLeft(impl.start(ctx)) { (d, ref) =>
      impl.addCas(d, ref, "ov", nv, ctx)
    }
    impl.tryPerform(d, ctx)
  }

  @Actor
  def writer1(r: ZZL_Result): Unit = {
    r.r1 = write("x")
  }

  @Actor
  def writer2(r: ZZL_Result): Unit = {
    r.r2 = write("y")
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    val ctx = impl.currentContext()
    val vs = refs.map(ref => impl.read(ref, ctx))
    val s = vs.toSet
    if (s.size == 1) {
      r.r3 = s.iterator.next()
    } else {
      throw new AssertionError(s"invalid values: ${s}")
    }
  }
}
