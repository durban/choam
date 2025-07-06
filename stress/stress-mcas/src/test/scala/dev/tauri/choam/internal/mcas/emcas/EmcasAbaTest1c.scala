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
import org.openjdk.jcstress.infra.results.LLLLLL_Result

@JCStressTest
@State
@Description("EMCAS: ABA problem 1/C (should fail if we don't use markers)")
@Outcomes(Array(
  new Outcome(id = Array("a, x, 0, 1, 0, x"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t1 reads its own new version"),
  new Outcome(id = Array("a, x, 0, 1, 1, x"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t1 reads t2's new version"),
  new Outcome(id = Array("a, y, 0, 1, 0, x"), expect = FORBIDDEN, desc = "non-linearizable result (1)"),
  new Outcome(id = Array("a, y, 0, 1, 1, x"), expect = FORBIDDEN, desc = "non-linearizable result (2)"),
))
class EmcasAbaTest1c {

  // This is a version of `EmcasAbaTest1` with
  // an additional `readVersion` at the end of
  // t1, so that `readVersion` racing with a
  // `readDirect` detaching is (hopefully) also
  // tested.

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
  def t1(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    val Some((r1v, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    Predef.assert(r1v == "a")
    val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("b"))
    val Some((r2v, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
    Predef.assert(r2v == "x")
    val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("y"))
    val newVersion = if (ctx.tryPerform(d4) == McasStatus.Successful) {
      d4.newVersion
    } else {
      Version.None
    }
    r.r5 = ctx.readVersion(r2)
    r.r3 = if (newVersion != Version.None) java.lang.Long.valueOf(newVersion) else "err"
  }

  @Actor
  def t2(r: LLLLLL_Result): Unit = {
    val r2 = this.r2
    val ctx = inst.currentContext()

    @tailrec
    def go(): Long = {
      val d0 = ctx.start()
      val Some((r1v, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
      r1v match {
        case "a" =>
          go() // wait for t1 to start;
          // this way we can make sure t2 is sequenced after
          // t1 (as t1 already acquired r1), but there can
          // still be overlap, see below
        case "b" =>
          // t1 started, but possibly didn't finish,
          // so our readValue may have helped it;
          // no we change the values back:
          val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("a"))
          val Some((r2v, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
          Predef.assert(r2v == "y")
          val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("x"))
          if (ctx.tryPerform(d4) == McasStatus.Successful) {
            // ok, return new version
            d4.newVersion
          } else {
            // this mustn't happen
            Version.None
          }
      }
    }

    val newVersion = go()

    // this maybe can detach the descriptor (if we don't use markers);
    // so if t1 is still running, it may continue performing its op
    // incorrectly (it sees the changed back "x", and continues with
    // changing it to "y"):
    r.r6 = ctx.readDirect(r2)

    r.r4 = if (newVersion != Version.None) java.lang.Long.valueOf(newVersion) else "err"
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    r.r1 = ctx.readDirect(r1)
    r.r2 = ctx.readDirect(r2)
    // normalize versions:
    val ver: Long = r.r5.asInstanceOf[java.lang.Long].longValue()
    r.r3 match {
      case t1NewVerBoxed: java.lang.Long =>
        val t1NewVer: Long = t1NewVerBoxed.longValue()
        r.r4 match {
          case t2NewVerBoxed: java.lang.Long =>
            val t2NewVer: Long = t2NewVerBoxed.longValue()
            r.r3 = 0
            r.r4 = t2NewVer - t1NewVer
            r.r5 = ver - t1NewVer
          case _ =>
            // error, we leave it alone
        }
      case _ =>
        // error, we leave it alone
    }
  }
}
