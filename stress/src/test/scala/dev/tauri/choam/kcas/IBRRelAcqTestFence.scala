/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results._

@JCStressTest
@State
@Description("IBR reservation rel/acq: store/load fence")
@Outcomes(Array(
  new Outcome(id = Array("0, 0"), expect = FORBIDDEN, desc = "Neither thread sees the other store"),
  new Outcome(id = Array("1, 0"), expect = ACCEPTABLE, desc = "Only actor1 sees the other store"),
  new Outcome(id = Array("0, 1"), expect = ACCEPTABLE, desc = "Only actor2 sees the other store"),
  new Outcome(id = Array("1, 1"), expect = ACCEPTABLE, desc = "Both threads see the other store")
))
class IBRRelAcqTestFence {

  import java.lang.invoke.VarHandle

  private[this] val res =
    new IBRReservation(0L)

  @Actor
  @deprecated("it calls `setLowerRelease`", since = "0")
  def actor1(r: JJ_Result): Unit = {
    this.res.setLowerRelease(1L) // release
    VarHandle.fullFence() // forbids reordering
    r.r1 = this.res.getUpper() // acquire
  }

  @Actor
  @deprecated("it calls `setUpperRelease`", since = "0")
  def actor2(r: JJ_Result): Unit = {
    this.res.setUpperRelease(1L) // release
    VarHandle.fullFence() // forbids reordering
    r.r2 = this.res.getLower() // acquire
  }
}
