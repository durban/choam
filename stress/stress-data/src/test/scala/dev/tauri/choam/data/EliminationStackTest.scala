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
package data

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import core.Rxn
import EliminationStack.TaggedEliminationStack

@JCStressTest
@State
@Description("Elimination stack: elimination")
@Outcomes(Array(
  new Outcome(id = Array("Left(()), Left(Some(a))"), expect = ACCEPTABLE, desc = "No exchange"),
  new Outcome(id = Array("Right(()), Right(Some(a))"), expect = ACCEPTABLE_INTERESTING, desc = "Exchange"),
))
class EliminationStackTest extends StressTestBase {

  private[this] val stack: TaggedEliminationStack[String] =
    EliminationStack.taggedFlaky[String]().unsafePerform(null, this.impl)

  private[this] final def _push(s: String): Rxn[Either[Unit, Unit]] =
    stack.push(s)

  private[this] val _tryPop: Rxn[Either[Option[String], Option[String]]] = {
    stack.tryPop.flatMap {
      case e @ (Left(Some(_)) | Right(Some(_))) => Rxn.pure(e)
      case Left(None) | Right(None) => Rxn.unsafe.retry
    }
  }

  @Actor
  def push(r: LL_Result): Unit = {
    r.r1 = _push("a").unsafePerform(null, this.impl)
  }

  @Actor
  def pop(r: LL_Result): Unit = {
    r.r2 = _tryPop.unsafePerform(null, this.impl)
  }
}
