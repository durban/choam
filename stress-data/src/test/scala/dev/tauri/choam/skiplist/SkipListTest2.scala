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
import org.openjdk.jcstress.infra.results.JJJJJJ_Result

@JCStressTest
@State
@Description("TimerSkipList insert/insert race")
@Outcomes(Array(
  new JOutcome(id = Array("1100, -9223372036854775679, 1100, -9223372036854775678, 1, 2"), expect = ACCEPTABLE_INTERESTING, desc = "insert1 won"),
  new JOutcome(id = Array("1100, -9223372036854775678, 1100, -9223372036854775679, 2, 1"), expect = ACCEPTABLE_INTERESTING, desc = "insert2 won"),
))
class SkipListTest2 {

  import SkipListHelper._

  private[this] val m = {
    val DELAY = 1024L
    val m = new TimerSkipList
    for (i <- 1 to 128) {
      m.insert(now = i.toLong, delay = DELAY, callback = newCallback(i.toLong, DELAY))
    }
    m
  }

  private[this] final val MAGIC = 972L

  private[this] val newCb1 =
    newCallback(128L, MAGIC)

  private[this] val newCb2 =
    newCallback(128L, MAGIC)

  @Actor
  def insert1(r: JJJJJJ_Result): Unit = {
    // the list contains times between 1025 and 1152, we insert at 1100:
    val cancel = m.insert(now = newCb1.now, delay = newCb1.delay, callback = newCb1).asInstanceOf[m.Canceller]
    r.r1 = cancel.triggerTime
    r.r2 = cancel.seqNo
  }

  @Actor
  def insert2(r: JJJJJJ_Result): Unit = {
    // the list contains times between 1025 and 1152, we insert at 1100:
    val cancel = m.insert(now = newCb2.now, delay = newCb2.delay, callback = newCb2).asInstanceOf[m.Canceller]
    r.r3 = cancel.triggerTime
    r.r4 = cancel.seqNo
  }

  @Arbiter
  def arbiter(r: JJJJJJ_Result): Unit = {
    // first remove all the items before the racy ones:
    while ({
      val cb = m.peekFirstQuiescent().asInstanceOf[MyCallback]
      cb.delay != MAGIC
    }) {
      m.pollFirstIfTriggered(now = 2048L)
    }
    // then look at the 2 racy inserts:
    val first = m.pollFirstIfTriggered(now = 2048L)
    val second = m.pollFirstIfTriggered(now = 2048L)
    r.r5 = if (first eq newCb1) 1L else if (first eq newCb2) 2L else -1L
    r.r6 = if (second eq newCb1) 1L else if (second eq newCb2) 2L else -1L
  }

  private[this] final def newCallback(now: Long, delay: Long): MyCallback = {
    new MyCallback(now, delay)
  }
}
