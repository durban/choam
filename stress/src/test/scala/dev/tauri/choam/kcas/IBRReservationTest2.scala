/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results._

import dev.tauri.choam.util.IBRReservationTest2Data

@JCStressTest
@State
@Description("IBR reservation should forbid freeing an object")
@Outcomes(Array(
  new Outcome(id = Array("false, false, false"), expect = ACCEPTABLE, desc = "Reader was late, but wasn't freed"),
  new Outcome(id = Array("true, false, false"), expect = ACCEPTABLE, desc = "Reader was late, was freed"),
  new Outcome(id = Array("false, true, false",
                         "true, true, false"), expect = FORBIDDEN, desc = "Reader was late, `end` was (also) freed"),
  new Outcome(id = Array("false, false, true"), expect = ACCEPTABLE, desc = "Reader found it, wasn't freed"),
  new Outcome(id = Array("true, false, true"), expect = ACCEPTABLE, desc = "Reader found it, later it was freed"),
  new Outcome(id = Array("false, true, true",
                        "true, true, true"), expect = FORBIDDEN, desc = "Reader found it, and seen it freed")
))
class IBRReservationTest2 {

  import IBRReservationTest2.TC

  /** New GC for every test, so different iterations won't interfere with each other */
  private[this] val gc = {
    new IBR[TC, IBRReservationTest2Data[TC]](0L) {
      def allocateNew() = new IBRReservationTest2Data[TC]
      def dynamicTest[A](a: A): Boolean = a.isInstanceOf[IBRReservationTest2Data[_]]
      def newThreadContext(): TC = {
        // start the counter close to epochFreq, to test epoch transitions:
        val ctr = ThreadLocalRandom.current().nextInt(IBR.epochFreq - 5, IBR.epochFreq + 5)
        new TC(this, ctr)
      }
    }
  }

  private[this] val begin =
    new IBRReservationTest2Data[TC]

  private[this] val end =
    new IBRReservationTest2Data[TC]

  private[this] val ref =
    new AtomicReference[IBRReservationTest2Data[TC]](begin)

  @Actor
  def read(r: ZZZ_Result): Unit = {
    val tc = this.gc.threadContext()
    tc.startOp()
    // since `startOp` contains a volatile store, the next lines can't be reordered before it;
    // otherwise we could see an already freed object (in theory...)
    val d = tc.readAcquire(this.ref)
    r.r2 = d.getFreedAcq()
    r.r3 = (d eq this.begin)
    tc.endOp()
  }

  @Actor
  def detach(r: ZZZ_Result): Unit = {
    val tc = this.gc.threadContext()
    tc.startOp()
    // simulate allocating `this.begin`:
    this.begin.setBirthEpochOpaque(this.gc.epochNumber)
    this.begin.allocate(tc)
    // detach and free it:
    assert(tc.cas(this.ref, this.begin, this.end))
    tc.retire(this.begin) // retire
    tc.endOp()
    tc.fullGc()
    r.r1 = this.begin.getFreedAcq()
  }
}

final object IBRReservationTest2 {

  final class TC(gc: IBR[TC, IBRReservationTest2Data[TC]], startCounter: Int)
    extends IBR.ThreadContext[TC, IBRReservationTest2Data[TC]](gc, startCounter)
}
