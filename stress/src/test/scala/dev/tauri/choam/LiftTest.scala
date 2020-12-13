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
import org.openjdk.jcstress.infra.results.LLL_Result

import kcas._

@KCASParams("Side effect in lifted 'functions' are not part of the reaction")
@Outcomes(Array(
  new Outcome(id = Array("foo, (x,x), (y,x)"), expect = ACCEPTABLE, desc = "act1 runs first, act2 reads written value"),
  new Outcome(id = Array("foo, (bar,x), (y,x)"), expect = ACCEPTABLE, desc = "act1 runs first, act2 reads stale value"),
  new Outcome(id = Array("y, (bar,foo), (x,x)"), expect = ACCEPTABLE, desc = "act2 runs first"),
  new Outcome(id = Array("y, (x,foo), (x,x)"), expect = ACCEPTABLE, desc = "act2 runs first, but reads modified value")
))
abstract class LiftTest(impl: KCAS) {

  private[this] val ref =
    Ref.mk("foo")

  private[this] var notReallyRef =
    "bar"

  private[this] val upd = ref.upd[String, String] { (ov, nv) =>
    (nv, ov)
  }

  private[this] val readAndUpd: React[String, (String, String)] = React.lift[String, String] { _ =>
    this.notReallyRef
  } * upd

  private[this] val updAndWrite: React[String, (String, Unit)] = upd * React.lift[String, Unit] { s =>
    this.notReallyRef = s
    ()
  }

  @Actor
  def act1(r: LLL_Result): Unit = {
    r.r1 = updAndWrite.unsafePerform("x")._1
  }

  @Actor
  def act2(r: LLL_Result): Unit = {
    r.r2 = readAndUpd.unsafePerform("y")
  }

  @Arbiter
  def abriter(r: LLL_Result): Unit = {
    r.r3 = (ref.getter.unsafeRun, this.notReallyRef)
  }
}
