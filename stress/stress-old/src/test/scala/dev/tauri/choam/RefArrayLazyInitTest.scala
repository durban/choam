/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

// @JCStressTest
@State
@Description("LazyRefArray should be safely initialized")
@Outcomes(Array(
  new Outcome(id = Array("0, 0"), expect = ACCEPTABLE, desc = "read first"),
  new Outcome(id = Array("1, x"), expect = ACCEPTABLE, desc = "read found it"),
))
class RefArrayLazyInitTest extends StressTestBase {

  // intentionally not volatile
  private[this] var arr: Ref.Array[String] =
    null

  @Actor
  def write(): Unit = {
    this.arr = Ref.unsafeLazyArray[String](4, "x")
  }

  @Actor
  def read(r: LL_Result): Unit = {
    this.arr match {
      case null =>
        // we're too early
        r.r1 = "0"
        r.r2 = "0"
      case a =>
        // we found something, so it should
        // be properly initialized
        r.r2 = a.unsafeGet(2).loc.unsafeGetVolatile()
        r.r1 = "1"
    }
  }
}
