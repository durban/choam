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

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LL_Result

import internal.mcas.{ Mcas, OsRng }
import core.Ref

@JCStressTest
@State
@Description("SingleCasTest")
@Outcomes(Array(
  new JOutcome(id = Array("rescinded, R"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
  new JOutcome(id = Array("rescinded, Rescinded"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
  new JOutcome(id = Array("FinishedEx, F"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class SingleCasTest {

  import SingleCasTest.{ NodeResult, mcas, rescinded, finishedEx }

  private[this] val ref =
    Ref.unsafe[NodeResult](null, Ref.AllocationStrategy.Padded, mcas.currentContext().refIdGen).loc

  @Actor
  def rescind(r: LL_Result): Unit = {
    val ctx = mcas.currentContext()
    val ref = this.ref
    if (ctx.singleCasDirect(ref, null, rescinded)) {
      r.r1 = "rescinded"
    } else {
      r.r1 = java.util.Objects.toString(ctx.readDirect(ref))
    }
  }

  @Actor
  def finish(r: LL_Result): Unit = {
    val ctx = mcas.currentContext()
    val d0 = ctx.start()
    val hwd = ctx.readIntoHwd(this.ref)
    if (hwd.ov eq null) {
      if (ctx.tryPerformOk(d0.add(hwd.withNv(finishedEx)))) {
        r.r2 = "F"
      } else {
        r.r2 = "R"
      }
    } else {
      r.r2 = hwd.ov.toString()
    }
  }
}

object SingleCasTest {

  sealed abstract class NodeResult

  final class FinishedEx extends NodeResult {
    final override def toString: String = "FinishedEx"
  }

  final class Rescinded extends NodeResult {
    final override def toString: String = "Rescinded"
  }

  val mcas: Mcas =
    Mcas.newSpinLockMcas(OsRng.mkNew(), 2)

  val finishedEx: NodeResult =
    new FinishedEx

  val rescinded: NodeResult =
    new Rescinded
}
