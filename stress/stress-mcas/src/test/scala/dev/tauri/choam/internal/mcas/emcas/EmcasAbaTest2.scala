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
import org.openjdk.jcstress.infra.results.LLLL_Result

@JCStressTest
@State
@Description("EMCAS: ABA problem 2 (should fail if we don't use markers)")
@Outcomes(Array(
  new Outcome(id = Array("a, x, true, true"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t1 won"),
  new Outcome(id = Array("b, y, true, true"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t2 won"),
  new Outcome(id = Array("a, y, true, true"), expect = FORBIDDEN, desc = "non-linearizable"),
))
class EmcasAbaTest2 {

  // This a version of `EmcasAbaTest1` that is (hopefully)
  // less dependent of implementation details; but since
  // we're not forcing the timing, the non-linearizable
  // result is VERY rare (in some runs it isn't observed
  // on x86_64 linux).

  private[this] val inst =
    StressTestBase.emcasInst

  private[this] val r1 = {
    val r = MemoryLocation.unsafeWithId("-")(21L)
    Predef.assert(inst.currentContext().builder().updateRef[String](r, _ => "a").tryPerformOk())
    r
  }

  private[this] val r2 = {
    val r = MemoryLocation.unsafeWithId("-")(42L)
    Predef.assert(inst.currentContext().builder().updateRef[String](r, _ => "x").tryPerformOk())
    r
  }

  Predef.assert(MemoryLocation.globalCompare(r1, r2) < 0) // ref1 < ref2

  @Actor
  def t1(r: LLLL_Result): Unit = {
    val ctx = inst.currentContext()

    def go(): Boolean = {
      val d0 = ctx.start()
      ctx.readMaybeFromLog(r1, d0, canExtend = true) match {
        case Some((_, d1)) =>
          val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("b"))
          ctx.readMaybeFromLog(r2, d2, canExtend = true) match {
            case Some((_, d3)) =>
              val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("y"))
              (ctx.tryPerform(d4) == McasStatus.Successful)
            case None =>
              go()
          }
        case None =>
          go()
      }
    }

    r.r3 = go() // must be true
    ctx.readDirect(r2) // detach
    ()
  }

  @Actor
  def t2(r: LLLL_Result): Unit = {
    val ctx = inst.currentContext()

    def go(): Boolean = {
      val d0 = ctx.start()
      ctx.readMaybeFromLog(r1, d0, canExtend = true) match {
        case Some((_, d1)) =>
          val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("a"))
          ctx.readMaybeFromLog(r2, d2, canExtend = true) match {
            case Some((_, d3)) =>
              val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("x"))
              (ctx.tryPerform(d4) == McasStatus.Successful)
            case None =>
              go()
          }
        case None =>
          go()
      }
    }

    r.r4 = go() // must be true
    ctx.readDirect(r2) // detach
    ()
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    val ctx = inst.currentContext()
    r.r1 = ctx.readDirect(r1)
    r.r2 = ctx.readDirect(r2)
  }
}
