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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLL_Result

// @JCStressTest
@State
@Description("Changes by the reaction must be visible in post-commit actions")
@Outcomes(Array(
  new Outcome(id = Array("(foo,bar), x, (x,x), y, (y,y)"), expect = ACCEPTABLE, desc = "u1 first, pc reads result"),
  new Outcome(id = Array("(foo,bar), y, (x,x), y, (y,y)"), expect = ACCEPTABLE, desc = "u1 first, pc reads u2 result"),
  new Outcome(id = Array("(y,y), x, (foo,bar), y, (x,x)"), expect = ACCEPTABLE, desc = "u2 first, pc reads result"),
  new Outcome(id = Array("(y,y), x, (foo,bar), x, (x,x)"), expect = ACCEPTABLE, desc = "u2 first, pc reads u1 result")
))
class PostCommitTest extends StressTestBase {

  private[this] val r1 =
    Ref.unsafe("foo")

  private[this] val r2 =
    Ref.unsafe("bar")

  private[this] val upd: Rxn[String, (String, String)] =
    r1.upd[String, String] { (ov, nv) => (nv, ov) } * r2.upd[String, String] { (ov, nv) => (nv, ov) }

  @Actor
  def upd1(r: LLLLL_Result): Unit = {
    val u1 = upd.postCommit(Rxn.lift[(String, String), Unit] { res =>
      r.r1 = res
      r.r2 = r1.get.unsafeRun(this.impl)
      ()
    })
    u1.unsafePerform("x", this.impl)
    ()
  }

  @Actor
  def upd2(r: LLLLL_Result): Unit = {
    val u2 = upd.postCommit(Rxn.lift[(String, String), Unit] { res =>
      r.r3 = res
      r.r4 = r1.get.unsafeRun(this.impl)
      ()
    })
    u2.unsafePerform("y", this.impl)
    ()
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    r.r5 = (r1.get.unsafeRun(this.impl), r2.get.unsafeRun(this.impl))
  }
}
