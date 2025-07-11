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
import org.openjdk.jcstress.infra.results.LLL_Result

import core.{ Rxn, Axn, Ref }
import EliminationStack.TaggedEliminationStack

@JCStressTest
@State
@Description("Elimination stack: no elimination if descriptors are not disjoint")
@Outcomes(Array(
  new Outcome(id = Array("Left(()), Left(Some(a)), 141"), expect = ACCEPTABLE_INTERESTING, desc = "No exchange"),
  new Outcome(id = Array("Right(()), Right(Some(a)), 141"), expect = FORBIDDEN, desc = "Exchange"),
))
class EliminationStackConflictTest extends StressTestBase {

  private[this] val stack: TaggedEliminationStack[String] =
    EliminationStack.taggedFlaky[String]().unsafeRun(this.impl)

  private[this] val ref: Ref[Int] =
    Ref(0).unsafeRun(this.impl)

  private[this] final def _push(s: String): Rxn[Either[Unit, Unit]] =
    ref.update(_ + 42) *> stack.push(s)

  private[this] val _tryPop: Axn[Either[Option[String], Option[String]]] = {
    stack.tryPop.flatMapF {
      case e @ (Left(Some(_)) | Right(Some(_))) => Axn.pure(e)
      case Left(None) | Right(None) => Rxn.unsafe.retry
    } <* ref.update(_ + 99)
  }

  @Actor
  def push(r: LLL_Result): Unit = {
    r.r1 = _push("a").unsafePerform(null, this.impl)
  }

  @Actor
  def pop(r: LLL_Result): Unit = {
    r.r2 = _tryPop.unsafePerform(null, this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = ref.get.unsafeRun(this.impl)
  }
}
