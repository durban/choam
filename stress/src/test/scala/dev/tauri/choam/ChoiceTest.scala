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

import kcas.Ref

@JCStressTest
@State
@Description("Only one side of a `+` should be visible")
@Outcomes(Array(
  new Outcome(id = Array("(b,bar), (b,bar), (a,rab)"), expect = ACCEPTABLE, desc = "read first"),
  new Outcome(id = Array("(b,bar), (a,rab), (a,rab)"), expect = ACCEPTABLE, desc = "write first")
))
class ChoiceTest extends StressTestBase {

  private[this] val ref0 =
    Ref.mk("b")

  private[this] val ref1 =
    Ref.mk("foo")

  private[this] val ref2 =
    Ref.mk("bar")

  private[this] val choice: React[Unit, (String, String)] = {
    val mod1 = ref0.modify { s => (s(0) + 1).toChar.toString }
    val mod2 = ref0.modify { s => (s(0) - 1).toChar.toString }
    val ch1 = ref1.modifyWith {
      case "this will never match" => React.ret("x")
      case _ => React.retry
    }
    val ch2 = ref2.modify(_.reverse)
    (mod1 * ch1) + (mod2 * ch2)
  }

  private[this] val read: React[Unit, (String, String)] =
    React.consistentRead(ref0, ref2)

  @Actor
  def write(r: LLL_Result): Unit = {
    r.r1 = choice.unsafeRun()
  }

  @Actor
  def read(r: LLL_Result): Unit = {
    r.r2 = read.unsafeRun()
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    require(ref1.getter.unsafeRun() eq "foo")
    r.r3 = read.unsafeRun()
  }
}
