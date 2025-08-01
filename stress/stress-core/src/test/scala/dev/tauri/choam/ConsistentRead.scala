/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import core.{ Rxn, Ref }

@JCStressTest
@State
@Description("Ref#get should be consistent")
@Outcomes(Array(
  new Outcome(id = Array("(x,y), (x1,y1)"), expect = ACCEPTABLE, desc = "Read old values"),
  new Outcome(id = Array("(x1,y1), (x1,y1)"), expect = ACCEPTABLE_INTERESTING, desc = "Read new values")
))
class ConsistentRead extends StressTestBase {

  private[this] val ref1 =
    Ref.unsafePadded("-", this.rig)

  private[this] val ref2 =
    Ref.unsafePadded("y", this.rig)

  private[this] val upd: Rxn[Unit] =
    ref1.update(_ + "1") *> ref2.update(_ + "1")

  private[this] val get: Rxn[(String, String)] =
    ref1.get * ref2.get

  this.init()

  private[this] final def init(): Unit = {
    val initRxn = ref1.update(_ => "x")
    initRxn.unsafePerform(this.impl)
  }

  @Actor
  def update(): Unit = {
    upd.unsafePerform(this.impl)
  }

  @Actor
  def read(r: LL_Result): Unit = {
    r.r1 = get.unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = (ref1.get.unsafePerform(this.impl), ref2.get.unsafePerform(this.impl))
  }
}
