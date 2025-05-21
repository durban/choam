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
import org.openjdk.jcstress.infra.results.LLL_Result

import core.{ Axn, Ref }

@JCStressTest
@State
@Description("Multiple writes should not be visible during a Rxn")
@Outcomes(Array(
  new Outcome(id = Array("b, a, c"), expect = ACCEPTABLE, desc = "Reader first"),
  new Outcome(id = Array("b, c, c"), expect = ACCEPTABLE_INTERESTING, desc = "Writer first"),
))
class MultipleWriteInRxn extends StressTestBase {

  private[this] val ref: Ref[String] =
    Ref.unsafePadded("a", this.rig)

  private[this] val write: Axn[String] =
    ref.update(_ => "b") >>> ref.modify(b => ("c", b))

  private[this] val read: Axn[String] =
    ref.unsafeDirectRead

  @Actor
  def writer(r: LLL_Result): Unit = {
    r.r1 = this.write.unsafePerform(null, this.impl)
  }

  @Actor
  def reader(r: LLL_Result): Unit = {
    r.r2 = this.read.unsafePerform(null, this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = this.impl.currentContext().readDirect(this.ref.loc)
  }
}
