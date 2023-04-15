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
import org.openjdk.jcstress.infra.results.JJJ_Result

@JCStressTest
@State
@Description("TimerSkipList pollFirstIfTriggered/pollFirstIfTriggered race")
@Outcomes(Array(
  new JOutcome(id = Array("1, 0, 0"), expect = ACCEPTABLE_INTERESTING, desc = "pollFirst1 won"),
  new JOutcome(id = Array("0, 1, 0"), expect = ACCEPTABLE_INTERESTING, desc = "pollFirst2 won"),
))
class SkipListTest4 {

  import SkipListHelper.Callback

  private[this] val headCb =
    newCallback()

  private[this] val secondCb =
    newCallback()

  private[this] val m = {
    val m = new SkipListMap[Long, Callback]
    // head is 1025L:
    m.insertTlr(1L + 1024L, headCb)
    // second is 1026L:
    m.insertTlr(2L + 1024L, secondCb)
    for (i <- 3 to 128) {
      m.insertTlr(i.toLong + 1024L, newCallback())
    }
    m
  }

  @Actor
  def pollFirst1(r: JJJ_Result): Unit = {
    val cb = m.pollFirstIfTriggered(2048L)
    r.r1 = if (cb eq headCb) 1L else if (cb eq secondCb) 0L else -1L
  }

  @Actor
  def pollFirst2(r: JJJ_Result): Unit = {
    val cb = m.pollFirstIfTriggered(2048L)
    r.r2 = if (cb eq headCb) 1L else if (cb eq secondCb) 0L else -1L
  }

  @Arbiter
  def arbiter(r: JJJ_Result): Unit = {
    val otherCb = m.pollFirstIfTriggered(2048L)
    r.r3 = if (otherCb eq headCb) -1L else if (otherCb eq secondCb) -1L else 0L
  }

  private[this] final def newCallback(): Callback = {
    new Function1[Right[Nothing, Unit], Unit] with Serializable {
      final override def apply(r: Right[Nothing, Unit]): Unit = ()
      final override def toString: String = s"Callback(#${this.##.toHexString})"
    }
  }
}
