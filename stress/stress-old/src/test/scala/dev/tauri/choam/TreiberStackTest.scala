/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.SyncIO

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import helpers.StackHelper
import ce._

// @JCStressTest
@State
@Description("Treiber stack pop/push should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("z, List(x)"), expect = ACCEPTABLE, desc = "Pop is the first"),
  new Outcome(id = Array("x, List(z)"), expect = ACCEPTABLE, desc = "Pop the pushed value")
))
class TreiberStackTest extends StressTestBase {

  private[this] val stack =
    StackHelper.treiberStackFromList[SyncIO, String](List("z")).unsafeRunSync()

  private[this] val _push =
    stack.push

  private[this] val tryPop =
    stack.tryPop

  @Actor
  def push(): Unit = {
    _push.unsafePerform("x", this.impl)
  }

  @Actor
  def pop(r: LL_Result): Unit = {
    r.r1 = tryPop.unsafeRun(this.impl).get
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = StackHelper.popAll[SyncIO, String](stack).unsafeRunSync()
  }
}
