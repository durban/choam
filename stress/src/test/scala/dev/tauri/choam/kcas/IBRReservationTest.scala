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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results._

@JCStressTest
@State
@Description("IBR reservation should forbid freeing an object")
@Outcomes(Array(
  new Outcome(id = Array("data, begin, .*"), expect = FORBIDDEN, desc = "Reader should've retried"),
  new Outcome(id = Array("data, data, false"), expect = ACCEPTABLE, desc = "Reader still holds it, wasn't freed"),
  new Outcome(id = Array("data, data, true"), expect = FORBIDDEN, desc = "Reader still holds it, but was freed"),
  new Outcome(id = Array("data, end, true"), expect = ACCEPTABLE, desc = "Reader was late, was freed"),
  new Outcome(id = Array("data, end, false"), expect = ACCEPTABLE_INTERESTING, desc = "Reader was late, but wasn't freed (publisher might still use it)")
))
class IBRReservationTest {

  import IBRReservationTest.{ Data }

  private[this] val gc =
    IBRReservationTest.gc

  private[this] val begin =
    new Data("begin")

  private[this] val end =
    new Data("end")

  private[this] val ref =
    new AtomicReference[Data](begin)

  private[this] val latch =
    new CountDownLatch(1)

  @Actor
  def publish(r: LLZ_Result): Unit = {
    val tc = this.gc.threadContext()
    tc.startOp()
    val d = tc.alloc() // allocate
    assert(tc.cas(this.ref, this.begin, d)) // publish
    tc.endOp()
    r.r1 = d
  }

  // Note: due to the `latch`, the methods
  // below need to be in this exact order,
  // otherwise jcstress deadlocks during the
  // sanity check.

  @Actor
  def detach(r: LLZ_Result): Unit = {
    val tc = this.gc.threadContext()
    tc.startOp()
    var d = tc.readAcquire(this.ref)
    while (d.toString ne "data") {
      d = tc.readAcquire(this.ref)
    }
    assert(tc.cas(this.ref, d, this.end)) // detach
    tc.retire(d) // retire
    tc.endOp()
    tc.fullGc()
    r.r3 = d.freed
    this.latch.countDown()
  }

  @Actor
  def read(r: LLZ_Result): Unit = {
    val tc = this.gc.threadContext()
    tc.startOp()
    var d = tc.readAcquire(this.ref)
    while (d.toString eq "begin") {
      d = tc.readAcquire(this.ref)
    }
    val success = d.toString == "data"
    if (success) {
      // no `endOp` yet, we keep holding the object for a while:
      this.latch.await()
    }
    tc.endOp()
    r.r2 = d
  }
}

final object IBRReservationTest {

  final class TC(gc: IBR[TC, Data], val tid: Long)
    extends IBR.ThreadContext[TC, Data](gc)

  final class Data(val value: String)
    extends IBRManaged[TC, Data] with Serializable {

    var freed: Boolean =
      false

    final override def toString(): String =
      this.value

    protected[kcas] def allocate(tc: TC): Unit =
      this.freed = false

    protected[kcas] def retire(tc: TC): Unit =
      ()

    protected[kcas] def free(tc: TC): Unit =
      this.freed = true
  }

  val gc = new IBR[TC, Data](0L) {
    def allocateNew(): Data = new Data("data")
    def dynamicTest[A](a: A): Boolean = a.isInstanceOf[Data]
    def newThreadContext(): TC = new TC(this, Thread.currentThread().getId())
  }
}
