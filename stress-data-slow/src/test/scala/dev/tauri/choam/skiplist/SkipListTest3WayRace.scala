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
import org.openjdk.jcstress.infra.results.JJ_Result

@JCStressTest
@State
@Description("TimerSkipList: 3-way insert/pollFirstIfTriggered/cancel race")
@Outcomes(Array(
  new JOutcome(id = Array("3, 2"), expect = ACCEPTABLE_INTERESTING, desc = "(cancel | insert), pollFirst"),
  new JOutcome(id = Array("1, 3"), expect = ACCEPTABLE_INTERESTING, desc = "pollFirst, (cancel | insert) OR insert, pollFirst, cancel"),
  new JOutcome(id = Array("2, 3"), expect = ACCEPTABLE_INTERESTING, desc = "cancel, pollFirst, insert"),
))
class SkipListTest3WayRace {

  import SkipListHelper.Callback

  /*
   * Concurrently (1) cancel the first item,
   * (2) insert a new one right after it, and
   * (3) deque the first item.
   */

  private[this] val secondCb =
    newCallback()

  private[this] val m = {
    val m = new SkipListMap[Long, Callback]
    m.insertTlr(2L + 1024L, secondCb)
    for (i <- 3 to 128) {
      m.insertTlr(i.toLong + 1024L, newCallback())
    }
    m
  }

  private[this] val headCb =
    newCallback()

  private[this] val headCanceller =
    m.insertTlr(1L + 1024L, headCb)

  private[this] val newCb =
    newCallback()

  @Actor
  def cancel(): Unit = {
    headCanceller.run()
  }

  @Actor
  def insert(): Unit = {
    // head is 1025L now, we insert another 1025L:
    m.insertTlr(1L + 1024L, newCb)
    ()
  }

  @Actor
  def pollFirst(r: JJ_Result): Unit = {
    r.r1 = longFromCb(m.pollFirstIfTriggered(2048L))
  }

  @Arbiter
  def arbiter(r: JJ_Result): Unit = {
    r.r2 = longFromCb(m.pollFirstIfTriggered(2048L))
  }

  private[this] final def longFromCb(cb: Callback): Long = {
    if (cb eq headCb) 1L
    else if (cb eq secondCb) 2L
    else if (cb eq newCb) 3L
    else -1L
  }

  private[this] final def newCallback(): Callback = {
    new Function1[Right[Nothing, Unit], Unit] with Serializable {
      final override def apply(r: Right[Nothing, Unit]): Unit = ()
      final override def toString: String = s"Callback(#${this.##.toHexString})"
    }
  }
}
