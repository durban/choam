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

// @JCStressTest
@State
@Description("ExchangerTest1")
@Outcomes(Array(
  new Outcome(id = Array("None, None"), expect = ACCEPTABLE, desc = "No exchange"),
  new Outcome(id = Array("Some(a), Some(8)"), expect = FORBIDDEN, desc = "Successful exchange")
))
class ExchangerTest1 extends StressTestBase {

  private[this] val ex: Exchanger[Int, String] =
    Exchanger.unsafe[Int, String]

  private[this] val intToString: React[Int, Option[String]] =
    ex.exchange.?

  private[this] val stringToInt: React[String, Option[Int]] =
    ex.dual.exchange.?

  @Actor
  def intProvider(r: LL_Result): Unit = {
    r.r1 = intToString.unsafePerform(8, this.impl)
  }

  @Actor
  def stringProvider(r: LL_Result): Unit = {
    r.r2 = stringToInt.unsafePerform("a", this.impl)
  }
}
