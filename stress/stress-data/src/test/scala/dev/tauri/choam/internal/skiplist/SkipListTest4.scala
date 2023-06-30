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
package internal
package skiplist

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.ZZZ_Result

@JCStressTest
@State
@Description("SkipListMap del/del race")
@Outcomes(Array(
  new JOutcome(id = Array("true, false, false"), expect = ACCEPTABLE_INTERESTING, desc = "del1 won"),
  new JOutcome(id = Array("false, true, false"), expect = ACCEPTABLE_INTERESTING, desc = "del2 won"),
))
class SkipListTest4 {

  private[this] final val KEY = 64L

  private[this] val m = {
    val m = new SkipListMap[Long, String]
    for (i <- 1 to 128) {
      m.put(i.toLong, i.toString)
    }
    m
  }

  @Actor
  def del1(r: ZZZ_Result): Unit = {
    r.r1 = m.del(KEY)
  }

  @Actor
  def del2(r: ZZZ_Result): Unit = {
    r.r2 = m.del(KEY)
  }

  @Arbiter
  def arbiter(r: ZZZ_Result): Unit = {
    r.r3 = m.get(KEY).isDefined
  }
}
