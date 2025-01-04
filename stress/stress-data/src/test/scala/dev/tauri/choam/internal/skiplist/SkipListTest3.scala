/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.LLL_Result

@JCStressTest
@State
@Description("SkipListMap put/del race")
@Outcomes(Array(
  new JOutcome(id = Array("Some(MAGIC), true, None"), expect = ACCEPTABLE_INTERESTING, desc = "put won (overwrite)"),
  new JOutcome(id = Array("None, true, Some(OVER)"), expect = ACCEPTABLE_INTERESTING, desc = "del won"),
))
class SkipListTest3 {

  private[this] final val KEY =
    1100L

  private[this] val m = {
    val DELAY = 1024L
    val m = new SkipListMap[Long, String]
    for (i <- 1 to 128) {
      val key = i.toLong + DELAY
      m.put(key, key.toString)
    }
    m.put(KEY, "MAGIC") // this will be removed/overwritten
    m
  }

  @Actor
  def put(r: LLL_Result): Unit = {
    r.r1 = m.put(KEY, "OVER")
  }

  @Actor
  def del(r: LLL_Result): Unit = {
    r.r2 = m.del(KEY)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = m.get(KEY)
  }
}
