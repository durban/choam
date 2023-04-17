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
import org.openjdk.jcstress.infra.results.LLL_Result

@JCStressTest
@State
@Description("SkipListMap put/put race")
@Outcomes(Array(
  new JOutcome(id = Array("Some(1100), Some(MYVAL_1), Some(MYVAL_2)"), expect = ACCEPTABLE_INTERESTING, desc = "insert1 won"),
  new JOutcome(id = Array("Some(MYVAL_2), Some(1100), Some(MYVAL_1)"), expect = ACCEPTABLE_INTERESTING, desc = "insert2 won"),
))
class SkipListTest2 {

  private[this] val m = {
    val DELAY = 1024L
    val m = new SkipListMap[Long, String]
    for (i <- 1 to 128) {
      val k = i.toLong + DELAY
      m.put(k, k.toString)
    }
    m
  }

  @Actor
  def insert1(r: LLL_Result): Unit = {
    // the list contains keys between 1025 and 1152, we insert at 1100:
    r.r1 = m.put(1100L, "MYVAL_1")
  }

  @Actor
  def insert2(r: LLL_Result): Unit = {
    // the list contains keys between 1025 and 1152, we insert at 1100:
    r.r2 = m.put(1100L, "MYVAL_2")
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = m.get(1100L)
  }
}
