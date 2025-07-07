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

import scala.runtime.BoxesRunTime.unboxToLong

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLLL_Result

@JCStressTest
@State
@Description("EMCAS: ABA problem 1/C (should fail if we don't use markers)")
@Outcomes(Array(
  new Outcome(id = Array("a, x, -, -, t1v, x"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t1 reads its own new version"),
  new Outcome(id = Array("a, x, -, -, t2v, x"), expect = ACCEPTABLE_INTERESTING, desc = "ok, t1 reads t2's new version"),
  new Outcome(id = Array("a, y, BAD, BAD, t1v, x"), expect = ACCEPTABLE_INTERESTING, desc = "FORBIDDEN: non-linearizable result + bad versions"),
  // Note: a non-linearizable result with t1 reading t2's version
  // doesn't seem to happen; due to the incorrect op, t1's version
  // seems to be the final version (which is of course completely
  // incorrect).
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
    val newVersion = tryPerformEmcas(ctx, d4)
    r.r5 = ctx.readVersion(r2)
    r.r3 = newVersion
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
          // now we change the values back:
          val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("a"))
          val Some((r2v, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
          Predef.assert(r2v == "y")
          val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("x"))
          tryPerformEmcas(ctx, d4)
      }
    }

    val newVersion = go()

    // this maybe can detach the descriptor (if we don't use markers);
    // so if t1 is still running, it may continue performing its op
    // incorrectly (it sees the changed back "x", and continues with
    // changing it to "y"):
    r.r6 = ctx.readDirect(r2)

    r.r4 = newVersion
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    val ctx = inst.currentContext()
    r.r1 = ctx.readDirect(r1)
    r.r2 = ctx.readDirect(r2)
    // normalize versions:
    val finalVer: Long = ctx.readVersion(r2)
    val ver: Long = unboxToLong(r.r5)
    val t1NewVer: Long = unboxToLong(r.r3)
    val t2NewVer: Long = unboxToLong(r.r4)
    Predef.assert(t1NewVer < t2NewVer)
    // also (t1NewVer < finalVer) should be true, but may not, if we don't use markers (see below)
    val finalNormalized = finalVer - t1NewVer
    val t2VerNormalized = t2NewVer - t1NewVer
    val verNormalized = ver - t1NewVer
    // The version read at the end of t1 should
    // either be the version committed by t1,
    // i.e., 0 normalized (if readVersion is fast
    // enough); or it should be the version
    // committed by t2, i.e., `t2VerNormalized`.
    if (verNormalized == 0L) {
      r.r5 = "t1v"
      saveT1AndFinal(t1NewVer = t1NewVer, finalVer = finalVer, r = r)
    } else if (verNormalized == t2VerNormalized) {
      r.r5 = "t2v"
      saveT1AndFinal(t1NewVer = t1NewVer, finalVer = finalVer, r = r)
    } else {
      // it is incorrect, save results for debugging:
      r.r5 = verNormalized
      r.r3 = finalNormalized
      r.r4 = t2VerNormalized
    }
  }

  private[this] final def saveT1AndFinal(t1NewVer: Long, finalVer: Long, r: LLLLLL_Result): Unit = {
      if (t1NewVer < finalVer) {
        r.r3 = "-"
        r.r4 = "-"
      } else if (t1NewVer == finalVer) {
        // this is incorrect, can happen if we don't use marks:
        r.r3 = "BAD"
        r.r4 = "BAD"
      } else {
        // we don't know what is this, but it's incorrect; just save them for debugging:
        r.r3 = t1NewVer.toString
        r.r4 = finalVer.toString
      }
  }

  private[this] final def tryPerformEmcas(ctx0: Mcas.ThreadContext, desc: AbstractDescriptor): Long = {
    val ctx = ctx0.asInstanceOf[EmcasThreadContext]
    val result = ctx.impl.tryPerformDebug0(desc, ctx, Consts.OPTIMISTIC)
    Predef.assert(EmcasStatusFunctions.isSuccessful(result)) // it should be a "proper" new version
    result
  }
}
