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
import org.openjdk.jcstress.infra.results.LL_Result

import kcas._

@JCStressTest
@State
@Description("updateWith and consistentRead should both execute atomically")
@Outcomes(Array(
  new Outcome(id = Array("(foo,bar), (bar,foo)"), expect = ACCEPTABLE, desc = "read before swap"),
  new Outcome(id = Array("(bar,foo), (bar,foo)"), expect = ACCEPTABLE, desc = "read after swap")
))
class UpdateTest extends StressTestBase {

  private[this] val r1 =
    Ref.unsafe("foo")

  private[this] val r2 =
    Ref.unsafe("bar")

  private[this] val sw: React[Unit, Unit] =
    React.swap(r1, r2)

  private[this] val read: React[Unit, (String, String)] =
    React.consistentRead(r1, r2)

  @Actor
  def swapper(): Unit = {
    sw.unsafeRun(this.impl)
  }

  @Actor
  def reader(r: LL_Result): Unit = {
    r.r1 = read.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = (r1.getter.unsafeRun(this.impl), r2.getter.unsafeRun(this.impl))
  }
}
