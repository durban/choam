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
package internal
package mcas
package emcas

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLL_Result

@JCStressTest
@State
@Description("EMCAS: ABA problem (should fail if we don't use markers)")
@Outcomes(Array(
  new Outcome(id = Array("a, x, true, true, x"), expect = ACCEPTABLE, desc = "the only acceptable result"),
))
class EmcasAbaTest {

  // This is the same scenario the comment in Emcas.scala mentions:
  // t1: [(r1, "a", "b"), (r2, "x", "y")]
  // t2: [(r1, "b", "a"), (r2, "y", "x")]

  private[this] val inst =
    StressTestBase.emcasInst

  private[this] val r1 = { // a -> b -> a
    val r = MemoryLocation.unsafeWithId("-")(21L)
    Predef.assert(inst.currentContext().builder().updateRef[String](r, _ => "a").tryPerformOk())
    r
  }

  private[this] val r2 = { // x -> y -> x
    val r = MemoryLocation.unsafeWithId("-")(42L)
    Predef.assert(inst.currentContext().builder().updateRef[String](r, _ => "x").tryPerformOk())
    r
  }

  Predef.assert(MemoryLocation.globalCompare(r1, r2) < 0) // ref1 < ref2

  @Actor
  def t1(r: LLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    val Some((r1v, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    Predef.assert(r1v == "a")
    val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("b"))
    val Some((r2v, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
    Predef.assert(r2v == "x")
    val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("y"))
    val ok = (ctx.tryPerform(d4) == McasStatus.Successful)
    r.r3 = ok // must be true
  }

  @Actor
  def t2(r: LLLLL_Result): Unit = {
    val ctx = inst.currentContext()

    @tailrec
    def go(): Boolean = {
      val d0 = ctx.start()
      val Some((r1v, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
      r1v match {
        case "a" =>
          go() // wait for t1
        case "b" =>
          val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("a"))
          val Some((r2v, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
          Predef.assert(r2v == "y")
          val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("x"))
          (ctx.tryPerform(d4) == McasStatus.Successful)
      }
    }

    r.r4 = go() // must be true
    r.r5 = ctx.readDirect(r2) // this maybe can detach the descriptor (if we don't use markers)
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    r.r1 = ctx.readDirect(r1)
    r.r2 = ctx.readDirect(r2)
  }
}
