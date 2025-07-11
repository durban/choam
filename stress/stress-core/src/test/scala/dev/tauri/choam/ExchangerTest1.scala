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
import org.openjdk.jcstress.infra.results.LLLL_Result

import core.{ Rxn, Exchanger, Ref }

@JCStressTest
@State
@Description("Simple exchange")
@Outcomes(Array(
  new Outcome(id = Array("None, None, None, None"), expect = ACCEPTABLE, desc = "No exchange"),
  new Outcome(id = Array("Some(Left(r)), Some(Left(r)), Some(Left(l)), Some(Left(l))"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange 1st-1st"),
  new Outcome(id = Array("Some(Left(r)), Some(Left(r)), Some(Right(l)), Some(Right(l))"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange 1st-2nd"),
  new Outcome(id = Array("Some(Right(r)), Some(Right(r)), Some(Left(l)), Some(Left(l))"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange 2nd-1st"),
  new Outcome(id = Array("Some(Right(r)), Some(Right(r)), Some(Right(l)), Some(Right(l))"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange 2nd-2nd"),
))
class ExchangerTest1 extends StressTestBase {

  private[this] val ex: Exchanger[String, String] =
    Rxn.unsafe.exchanger[String, String].unsafePerform(this.impl)

  private[this] val leftPc: Ref[Option[Either[String, String]]] =
    Ref[Option[Either[String, String]]](null).unsafePerform(this.impl)

  private[this] val rightPc: Ref[Option[Either[String, String]]] =
    Ref[Option[Either[String, String]]](null).unsafePerform(this.impl)

  private[this] final def leftSide(s: String): Rxn[Option[Either[String, String]]] = {
    val once = ex.exchange(s)
    (once.map(Left(_)) + once.map(Right(_))).?.postCommit(leftPc.set1)
  }

  private[this] final def rightSide(s: String): Rxn[Option[Either[String, String]]] = {
    val once = ex.dual.exchange(s)
    (once.map(Left(_)) + once.map(Right(_))).?.postCommit(rightPc.set1)
  }

  @Actor
  def left(r: LLLL_Result): Unit = {
    r.r1 = leftSide("l").unsafePerform(this.impl)
  }

  @Actor
  def right(r: LLLL_Result): Unit = {
    r.r3 = rightSide("r").unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r2 = leftPc.get.unsafePerform(this.impl)
    r.r4 = rightPc.get.unsafePerform(this.impl)
  }
}
