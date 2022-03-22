/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas
package emcas

import java.util.concurrent.atomic.AtomicInteger

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LL_Result

@JCStressTest
@State
@Description("Emcas status/array")
@Outcomes(Array(
  new Outcome(id = Array("abcd, 0"), expect = ACCEPTABLE, desc = ""),
  new Outcome(id = Array("abcd, 1"), expect = ACCEPTABLE, desc = ""),
  new Outcome(id = Array("null, 0"), expect = FORBIDDEN, desc = ""),
  new Outcome(id = Array("null, 1"), expect = ACCEPTABLE, desc = ""),
))
class OrderingTest {

  private[this] var str: String =
    "abcd"

  private[this] val flag: AtomicInteger =
    new AtomicInteger(0)

  @Actor
  def finalizer(): Unit = {
    assert(this.flag.compareAndSet(0, 1))
    this.str = null
  }

  @Actor
  def reader(r: LL_Result): Unit = {
    val s: String = this.str
    val f: Int = if (s == null) {
      this.flag.compareAndExchange(0, 42)
    } else {
      this.flag.get()
    }
    r.r1 = s
    r.r2 = f
  }
}
