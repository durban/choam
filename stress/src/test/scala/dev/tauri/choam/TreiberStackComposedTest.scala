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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

@JCStressTest
@State
@Description("Treiber stack composed pop/push should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("(Some(z1),Some(z2)), List(x, y), List(x, y)",
                         "(Some(z1),Some(z2)), List(y, x), List(y, x)"), expect = ACCEPTABLE, desc = "Pop is the first"),
  new Outcome(id = Array("(Some(x),Some(x)), List(y, z1), List(y, z2)",
                         "(Some(y),Some(y)), List(x, z1), List(x, z2)"), expect = ACCEPTABLE, desc = "Pop one of the pushed values")
))
class TreiberStackComposedTest extends StressTestBase {

  private[this] val stack1 =
    new TreiberStack[String](List("z1"))

  private[this] val stack2 =
    new TreiberStack[String](List("z2"))

  private[this] val push =
    stack1.push * stack2.push

  private[this] val tryPop =
    stack1.tryPop * stack2.tryPop

  @Actor
  def push1(): Unit = {
    push.unsafePerform("x", this.impl)
    ()
  }

  @Actor
  def push2(): Unit = {
    push.unsafePerform("y", this.impl)
    ()
  }

  @Actor
  def pop(r: LLL_Result): Unit = {
    r.r1 = tryPop.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r2 = stack1.unsafeToList(this.impl)
    r.r3 = stack2.unsafeToList(this.impl)
  }
}
