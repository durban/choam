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
import org.openjdk.jcstress.infra.results.LLLL_Result

@JCStressTest
@State
@Description("SkipListMap: 3-way get/put/del race")
@Outcomes(Array(
  new JOutcome(id = Array("Some(64), Some(64), true, None"), expect = ACCEPTABLE_INTERESTING, desc = "get, put, del"),
  new JOutcome(id = Array("Some(64), None, true, Some(VAL)"), expect = ACCEPTABLE_INTERESTING, desc = "get, del, put"),
  new JOutcome(id = Array("Some(VAL), Some(64), true, None"), expect = ACCEPTABLE_INTERESTING, desc = "put, get, del"),
  new JOutcome(id = Array("None, Some(64), true, None"), expect = ACCEPTABLE_INTERESTING, desc = "put, del, get"),
  new JOutcome(id = Array("None, None, true, Some(VAL)"), expect = ACCEPTABLE_INTERESTING, desc = "del, get, put"),
  new JOutcome(id = Array("Some(VAL), None, true, Some(VAL"), expect = ACCEPTABLE_INTERESTING, desc = "del, put, get"),
))
class SkipListTest3WayRace {

  private[this] final val KEY =
    64L

  private[this] val m = {
    val m = new SkipListMap[Long, String]
    for (i <- 1L to 128L) {
      m.put(i, i.toString)
    }
    m
  }

  @Actor
  def get(r: LLLL_Result): Unit = {
    r.r1 = m.get(KEY)
  }

  @Actor
  def put(r: LLLL_Result): Unit = {
    r.r2 = m.put(KEY, "VAL")
  }

  @Actor
  def del(r: LLLL_Result): Unit = {
    r.r3 = m.del(KEY)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r4 = m.get(KEY)
  }
}
