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

// TODO: More Exchanger stress tests:
// TODO: - test the atomicity/visibility of the 2 joined reactions
// TODO: - postCommit actions on both sides
// TODO:   - the 2 sides must be atomic
// TODO:   - the pc actions must not

@JCStressTest
@State
@Description("ExchangerTest1")
@Outcomes(Array(
  new Outcome(id = Array("None, None"), expect = ACCEPTABLE, desc = "No exchange"),
  new Outcome(id = Array("Some(r), Some(l)"), expect = ACCEPTABLE_INTERESTING, desc = "Successful exchange")
))
class ExchangerTest1 extends StressTestBase {

  private[this] val ex: Exchanger[String, String] =
    Exchanger.unsafe[String, String]

  private[this] val left: Rxn[String, Option[String]] =
    ex.exchange.?

  private[this] val lefts: Rxn[String, Option[String]] =
    left + left

  private[this] val right: Rxn[String, Option[String]] =
    ex.dual.exchange.?

  private[this] val rights: Rxn[String, Option[String]] =
    right + right

  @Actor
  def left(r: LL_Result): Unit = {
    r.r1 = lefts.unsafePerform("l", this.impl)
  }

  @Actor
  def right(r: LL_Result): Unit = {
    r.r2 = rights.unsafePerform("r", this.impl)
  }
}
