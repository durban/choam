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
import java.util.concurrent.{ ThreadLocalRandom, CountDownLatch }

import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results._

@JCStressTest
@State
@Description("IBR write/cas must be modified to be used with EMCAS")
@Outcomes(Array(
  new Outcome(id = Array("true, (false|true), 1"), expect = FORBIDDEN, desc = "Was freed"),
  new Outcome(id = Array("false, false, 1"), expect = ACCEPTABLE, desc = "Was not freed"),
  new Outcome(id = Array("(false|true), false, 2"), expect = ACCEPTABLE, desc = "Too late"),
  // This is not acceptable, it's why extra care is needed (see IMPORTANT below):
  new Outcome(id = Array("true, false, -2"), expect = FORBIDDEN, desc = "Was replaced by a String")
))
class IBRExtensionTest {

  import IBRExtensionTest._

  /** New GC for every test, so different iterations won't interfere with each other */
  private[this] val gc = new IBR[TC, Data](0L) {
    def allocateNew(): Data = new Data()
    def dynamicTest[A](a: A): Boolean = a.isInstanceOf[Data]
    def newThreadContext(): TC = {
      // start the counter close to epochFreq, to test epoch transitions:
      val ctr = ThreadLocalRandom.current().nextInt(IBR.epochFreq - 5, IBR.epochFreq + 5)
      new TC(this, ctr)
    }
  }

  private[this] val (ref, origD) = {
    val r = new AtomicReference[AnyRef]
    val tc = gc.threadContext()
    tc.startOp()
    val d = tc.alloc()
    d.ref = r
    assert(tc.cas(r, null, d))
    tc.endOp()
    (r, d)
  }

  private[this] val latch =
    new CountDownLatch(1)

  @Actor
  def writer(r: ZZI_Result): Unit = {
    val tc = gc.threadContext()
    tc.startOp()
    val d2 = try {
      // finalize descriptor:
      val d = tc.readAcquire(ref).asInstanceOf[Data]
      assert(d.status.compareAndSet(EMCASStatus.ACTIVE, EMCASStatus.SUCCESSFUL))
      // retire descriptor:
      tc.retire(d)
      // change it to another descriptor:
      val d2 = tc.alloc()
      d2.ref = ref
      // IMPORTANT: the next line is necessary, otherwise `d` could be
      // freed while the other thread is still using it.
      d2.setBirthEpochOpaque(Math.min(d.getBirthEpochOpaque(), d2.getBirthEpochOpaque()))
      // replace it with CAS:
      assert(tc.cas(ref, d, d2))
      // finalize and retire that one too:
      assert(d2.status.compareAndSet(EMCASStatus.ACTIVE, EMCASStatus.FAILED))
      tc.retire(d2)
      d2
    } finally tc.endOp()
    // try to free it:
    tc.fullGc()
    r.r1 = d2.freed
    latch.countDown()
  }

  @Actor
  def reader(r: ZZI_Result): Unit = {
    val tc = gc.threadContext()
    tc.startOp()
    try {
      tc.readAcquire(ref) match {
        case d: Data =>
          if (d eq this.origD) {
            // found the descriptor
            if (d.status.get() eq EMCASStatus.ACTIVE) {
              // it's still active
              r.r2 = d.freed
              r.r3 = 1
              latch.await() // wait for `writer` to run GC
              // re-read ref, it must be a descriptor (since we didn't release `d`):
              tc.readAcquire(ref) match {
                case _: Data =>
                  () // OK
                case s: String =>
                  if (s == "failure") r.r3 = -2
                  else r.r3 = -3
                case _ =>
                  r.r3 = -4
              }
            } else {
              // we're too late
              r.r3 = 2
            }
          } else {
            // we're too late
            r.r3 = 2
          }
        case _: String =>
          // we're too late
          r.r3 = 2
        case _ =>
          // unexpected
          r.r3 = -1
      }
    } finally tc.endOp()
  }
}

final object IBRExtensionTest {

  final class Data(
    val status: AtomicReference[EMCASStatus] = new AtomicReference(EMCASStatus.ACTIVE)
  ) extends IBRManaged[TC, Data] {

    @volatile
    var ref: AtomicReference[AnyRef] = _

    @volatile
    var freed: Boolean = false

    override def allocate(tc: TC): Unit = {
      this.freed = false
      tc.write(this.status, EMCASStatus.ACTIVE)
    }

    override def retire(tc: TC): Unit =
      ()

    override def free(tc: TC): Unit = {
      val s = this.status.get()
      val msg = if (s eq EMCASStatus.SUCCESSFUL) {
        "success"
      } else if (s eq EMCASStatus.FAILED) {
        "failure"
      } else {
        throw new IllegalStateException
      }
      this.ref.compareAndSet(this, msg) // if it fails, it was already replaced
      this.freed = true
    }

    override def toString: String =
      s"Data(freed = ${this.freed})"
  }

  final class TC(gc: IBR[TC, Data], startCounter: Int)
    extends IBR.ThreadContext[TC, Data](gc, startCounter)
}
