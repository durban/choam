/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZL_Result

@JCStressTest
@State
@Description("k-CAS should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("true, false, x"), expect = ACCEPTABLE_INTERESTING, desc = "writer1 succeeded"),
  new Outcome(id = Array("false, true, y"), expect = ACCEPTABLE_INTERESTING, desc = "writer2 succeeded"),
))
class McasTest extends StressTestBase {

  private[this] val refs: List[MemoryLocation[String]] =
    List.fill(7)(MemoryLocation.unsafe("ov"))

  private def write(nv: String): Boolean = {
    val ctx = impl.currentContext()
    val d = refs.foldLeft(ctx.start()) { (d, ref) =>
      ctx.addCasFromInitial(d, ref, "ov", nv)
    }
    ctx.tryPerformInternal(d) == McasStatus.Successful
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
    val vs = refs.map(ref => ctx.readDirect(ref))
    val s = vs.toSet
    if (s.size == 1) {
      r.r3 = s.iterator.next()
    } else {
      r.r3 = s.toString
    }
  }
}
