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
import org.openjdk.jcstress.infra.results.LLL_Result

import kcas._

@JCStressTest
@State
@Description("Computed reagents should be executed atomically")
@Outcomes(Array(
  new Outcome(id = Array("www, (foo,bar), (www,y)"), expect = ACCEPTABLE, desc = "Writer runs first; reader sees old values"),
  new Outcome(id = Array("www, (www,y), (www,y)"), expect = ACCEPTABLE, desc = "Writer runs first; reader sees new values"),
  new Outcome(id = Array("www, (www,bar), (www,y)"), expect = ACCEPTABLE, desc = "Writer runs first; reader sees new/old values"),
  new Outcome(id = Array("foo, (foo,bar), (www,x)"), expect = ACCEPTABLE, desc = "Computed runs first; reader sees old values"),
  new Outcome(id = Array("foo, (www,x), (www,x)"), expect = ACCEPTABLE, desc = "Computed runs first; reader sees new values"),
  new Outcome(id = Array("foo, (www,bar), (www,x)"), expect = ACCEPTABLE, desc = "Computed runs first; reader sees new/old values"),
  new Outcome(id = Array("foo, (foo,x), (www,x)"), expect = ACCEPTABLE, desc = "Computed runs first; reader sees old/new values")
))
class ComputedTest extends StressTestBase {

  private[this] val r1 =
    Ref.mk("foo")

  private[this] val r2 =
    Ref.mk("bar")

  private[this] val write =
    r1.upd[String, String] { (ov, nv) => (nv, ov) }

  private[this] val w1 =
    r2.upd[Any, String] { (ov, _) => ("x", ov) }

  private[this] val w2 =
    r2.upd[Any, String] { (ov, _) => ("y", ov) }

  private[this] val computed: React[Any, String] = {
    React.invisibleRead(r1) >>> React.computed[String, String] { a =>
      val w = if (a eq "foo") w1 else w2
      (w * React.cas(r1, a, a)).map { _ => a }
    }
  }

  private[this] val consistentRead: React[Unit, (String, String)] =
    React.consistentRead(r1, r2)

  @Actor
  def writer(): Unit = {
    write.unsafePerform("www", this.impl)
    ()
  }

  @Actor
  def computer(r: LLL_Result): Unit = {
    r.r1 = computed.unsafeRun(this.impl)
  }

  @Actor
  def reader(r: LLL_Result): Unit = {
    r.r2 = consistentRead.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = (r1.getter.unsafeRun(this.impl), r2.getter.unsafeRun(this.impl))
  }
}
