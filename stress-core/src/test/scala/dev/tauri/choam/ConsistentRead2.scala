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
import org.openjdk.jcstress.infra.results.LL_Result

@JCStressTest
@State
@Description("Ref#get should be consistent (after another op)")
@Outcomes(Array(
  new Outcome(id = Array("(x,y), (x1,y1)"), expect = ACCEPTABLE, desc = "Read old values"),
  new Outcome(id = Array("(x1,y1), (x1,y1)"), expect = ACCEPTABLE_INTERESTING, desc = "Read new values")
))
class ConsistentRead2 extends StressTestBase {

  private[this] val ref1 =
    Ref.unsafe("-")

  private[this] val ref2 =
    Ref.unsafe("-")

  private[this] val upd: Axn[Unit] =
    ref1.update(_ + "1") >>> ref2.update(_ + "1")

  private[this] val get: Axn[(String, String)] =
    ref1.get * ref2.get

  this.init()

  private[this] def init(): Unit = {
    val initRxn = ref1.update(_ => "x") >>> ref2.update(_ => "y")
    initRxn.unsafeRun(this.impl)
  }

  @Actor
  def update(): Unit = {
    upd.unsafeRun(this.impl)
  }

  @Actor
  def read(r: LL_Result): Unit = {
    r.r1 = get.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = (ref1.get.unsafeRun(this.impl), ref2.get.unsafeRun(this.impl))
  }
}
