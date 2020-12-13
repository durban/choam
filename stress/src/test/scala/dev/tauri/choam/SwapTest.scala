/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import org.openjdk.jcstress.annotations.{ Actor, Arbiter, Outcome }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import kcas._

@KCASParams("React.swap (updWith) should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("(x,y), (y,x)"), expect = ACCEPTABLE, desc = "Read before swap"),
  new Outcome(id = Array("(y,x), (y,x)"), expect = ACCEPTABLE, desc = "Read after swap")
))
abstract class SwapTest(impl: KCAS) {

  private[this] val ref1 =
    Ref.mk("x")

  private[this] val ref2 =
    Ref.mk("y")

  private[this] val sw: React[Unit, Unit] =
    React.swap(ref1, ref2)

  private[this] val rd =
    React.consistentRead(ref1, ref2)

  @Actor
  def swap(): Unit = {
    sw.unsafeRun
  }

  @Actor
  def read(r: LL_Result): Unit = {
    r.r1 = rd.unsafeRun
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = (ref1.getter.unsafeRun, ref2.getter.unsafeRun)
  }
}
