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
package core

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results._

// @JCStressTest
@State
@Description("The 2 joined reactions must be atomic")
@Outcomes(Array(
  new Outcome(id = Array("l0, r0, l0, r0"), expect = ACCEPTABLE, desc = "No exchange, reader first"),
  new Outcome(id = Array("l0, r0, l1, r0"), expect = ACCEPTABLE, desc = "No exchange, reader sees left"),
  new Outcome(id = Array("l0, r0, l0, r1"), expect = ACCEPTABLE, desc = "No exchange, reader sees right"),
  new Outcome(id = Array("l0, r0, l1, r1"), expect = ACCEPTABLE, desc = "No exchange, reader sees both"),
  new Outcome(id = Array("lx, rx, l0, r0"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange, reader first"),
  new Outcome(id = Array("lx, rx, rp, lp"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange, reader sees exchange"),
  new Outcome(id = Array("lx, rx, l0, lp"), expect = FORBIDDEN, desc = "Exchange, reader sees partial values"),
  new Outcome(id = Array("lx, rx, rp, r0"), expect = FORBIDDEN, desc = "Exchange, reader sees partial values"),
))
class ExchangerTest2 extends StressTestBase {

  private[this] val ex: Exchanger[String, String] =
    Exchanger.unsafe[String, String]

  private[this] val rl: Ref[String] =
    Ref.unsafe("l0")

  private[this] val left: Rxn[String, String] = {
    ex.exchange.?.flatMapF {
      case Some(s) => rl.modify(_ => (s, "lx"))
      case None => rl.getAndUpdate(_ => "l1")
    }
  }

  private[this] val rr: Ref[String] =
    Ref.unsafe("r0")

  private[this] val right: Rxn[String, String] = {
    ex.dual.exchange.?.flatMapF {
      case Some(s) => rr.modify(_ => (s, "rx"))
      case None => rr.getAndUpdate(_ => "r1")
    }
  }

  private[this] val read: Rxn[Any, (String, String)] =
    Ref.consistentRead(rl, rr)

  @Actor
  def left(r: LLLL_Result): Unit = {
    r.r1 = left.unsafePerform("lp", this.impl)
  }

  @Actor
  def right(r: LLLL_Result): Unit = {
    r.r2 = right.unsafePerform("rp", this.impl)
  }

  @Actor
  def reader(r: LLLL_Result): Unit = {
    val res = read.unsafePerform((), this.impl)
    r.r3 = res._1
    r.r4 = res._2
  }
}
