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
import org.openjdk.jcstress.infra.results._

@JCStressTest
@State
@Description("Exchange with postCommit")
@Outcomes(Array(
  new Outcome(id = Array("None, None, (0,0), (2,2)"), expect = ACCEPTABLE, desc = "No exchange, reader first"),
  new Outcome(id = Array("None, None, (0,1), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "No exchange (separate reactions), reader during"),
  new Outcome(id = Array("None, None, (1,0), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "No exchange (separate reactions), reader during"),
  new Outcome(id = Array("None, None, (0,2), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "No exchange (separate reactions), reader during"),
  new Outcome(id = Array("None, None, (2,0), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "No exchange (separate reactions), reader during"),
  new Outcome(id = Array("None, None, (1,1), (2,2)"), expect = ACCEPTABLE, desc = "No exchange, reader after commit"),
  new Outcome(id = Array("None, None, (2,1), (2,2)"), expect = ACCEPTABLE, desc = "No exchange, reader after one postCommit"),
  new Outcome(id = Array("None, None, (1,2), (2,2)"), expect = ACCEPTABLE, desc = "No exchange, reader after other postCommit"),
  new Outcome(id = Array("None, None, (2,2), (2,2)"), expect = ACCEPTABLE, desc = "No exchange, reader after both postCommits"),
  new Outcome(id = Array("Some(r), Some(l), (0,0), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange, reader first"),
  new Outcome(id = Array("Some(r), Some(l), (1,1), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange, reader after commit"),
  new Outcome(id = Array("Some(r), Some(l), (2,1), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange, reader after one postCommit"),
  new Outcome(id = Array("Some(r), Some(l), (1,2), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange, reader after other postCommit"),
  new Outcome(id = Array("Some(r), Some(l), (2,2), (2,2)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange, reader after both postCommits"),
))
class ExchangerTest3 extends StressTestBase {

  private[this] val ex: Exchanger[String, String] =
    Exchanger.unsafe[String, String]

  private[this] val leftCtr =
    Ref.unsafe(0)

  private[this] val left: Rxn[String, Option[String]] =
    (ex.exchange.? <* leftCtr.update(_ + 1)).postCommit(leftCtr.update(_ + 1))

  private[this] val rightCtr =
    Ref.unsafe(0)

  private[this] val right: Rxn[String, Option[String]] =
    (ex.dual.exchange.? <* rightCtr.update(_ + 1)).postCommit(rightCtr.update(_ + 1))

  private[this] val consistentRead: Axn[(Int, Int)] =
    Rxn.consistentRead(leftCtr, rightCtr)

  @Actor
  def left(r: LLLL_Result): Unit = {
    r.r1 = left.unsafePerform("l", this.impl)
  }

  @Actor
  def right(r: LLLL_Result): Unit = {
    r.r2 = right.unsafePerform("r", this.impl)
  }

  @Actor
  def read(r: LLLL_Result): Unit = {
    r.r3 = consistentRead.unsafePerform(null, this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r4 = consistentRead.unsafePerform(null, this.impl)
  }
}
