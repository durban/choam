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
@Description("SkipListMap get/del race")
@Outcomes(Array(
  new JOutcome(id = Array("Some(64), true, None"), expect = ACCEPTABLE_INTERESTING, desc = "get won"),
  new JOutcome(id = Array("None, true, None"), expect = ACCEPTABLE_INTERESTING, desc = "del won"),
))
class SkipListTest5 {

  private[this] final val KEY =
    64L

  private[this] val m = {
    val m = new SkipListMap[Long, String]
    for (k <- 1L to 128L) {
      m.put(k, k.toString)
    }
    m
  }

  @Actor
  def get(r: LLL_Result): Unit = {
    r.r1 = m.get(KEY)
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
