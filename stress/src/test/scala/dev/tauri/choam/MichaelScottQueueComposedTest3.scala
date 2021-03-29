/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import kcas.Ref

@JCStressTest
@State
@Description("MichaelScottQueue tricky enq/deq")
@Outcomes(Array(
  new Outcome(id = Array("(None,false), true"), expect = ACCEPTABLE, desc = "deq was first"),
  new Outcome(id = Array("(Some(a),true), false"), expect = ACCEPTABLE, desc = "enq was first"),
  new Outcome(id = Array("(None,true), false"), expect = FORBIDDEN, desc = "enq was first, but deq sees empty")
))
class MichaelScottQueueComposedTest3 extends MsQueueStressTestBase {

  private[this] val queue =
    this.newQueue[String]()

  private[this] val latch =
    Ref.unsafe[Boolean](false)

  private[this] val dummy =
    Ref.unsafe[Int](0)

  private[this] val deq =
    queue.tryDeque * (dummy.update { _ + 1 }.flatMap { _ => latch.getAndUpdate(_ => true) })

  private[this] val enq =
    (queue.enqueue * latch.getAndUpdate(_ => true)).map(_._2)

  @Actor
  def deq(r: LL_Result): Unit = {
    r.r1 = deq.unsafePerform((), this.impl)
  }

  @Actor
  def enq(r: LL_Result): Unit = {
    r.r2 = enq.unsafePerform("a", this.impl)
  }
}
