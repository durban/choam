/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package skiplist

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.JJJJ_Result

@JCStressTest
@State
@Description("TimerSkipList insert/cancel race")
@Outcomes(Array(
  new JOutcome(id = Array("1100, -9223372036854775678, 1, 1"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class SkipListTest3 {

  import SkipListHelper._

  private[this] val m = {
    val DELAY = 1024L
    val m = new TimerSkipList
    for (i <- 1 to 128) {
      m.insertTlr(now = i.toLong, delay = DELAY, callback = newCallback(i.toLong, DELAY))
    }
    m
  }

  private[this] final val MAGIC = 972L

  val cancelledCb: Callback =
    newCallback(128L, MAGIC)

  private[this] val canceller: Runnable =
    m.insertTlr(128L, MAGIC, cancelledCb)

  private[this] val newCb: MyCallback =
    newCallback(128L, MAGIC)

  @Actor
  def insert(r: JJJJ_Result): Unit = {
    // the list contains times between 1025 and 1152, we insert at 1100:
    val cancel = m.insertTlr(now = newCb.now, delay = newCb.delay, callback = newCb).asInstanceOf[m.Node]
    r.r1 = cancel.triggerTime
    r.r2 = cancel.sequenceNum
  }

  @Actor
  def cancel(): Unit = {
    canceller.run()
  }

  @Arbiter
  def arbiter(r: JJJJ_Result): Unit = {
    // first remove all the items before the racy ones:
    while ({
      val cb = m.peekFirstQuiescent().asInstanceOf[MyCallback]
      cb.delay != MAGIC
    }) {
      m.pollFirstIfTriggered(now = 2048L)
    }
    // then look at the inserted item:
    val cb = m.pollFirstIfTriggered(now = 2048L)
    r.r3 = if (cb eq newCb) 1L else 0L
    // the cancelled one must be missing:
    val other = m.pollFirstIfTriggered(now = 2048L)
    r.r4 = if (other eq cancelledCb) 0L else if (other eq newCb) -1L else 1L
  }

  private[this] final def newCallback(now: Long, delay: Long): MyCallback = {
    new MyCallback(now, delay)
  }
}
