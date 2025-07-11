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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import cats.effect.SyncIO

@JCStressTest
@State
@Description("MsQueue enq/deq should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("None, List(x, y)"), expect = ACCEPTABLE, desc = "deq, enq1, enq2"),
  new Outcome(id = Array("None, List(y, x)"), expect = ACCEPTABLE, desc = "deq, enq2, enq1"),
  new Outcome(id = Array("Some(x), List(y)"), expect = ACCEPTABLE_INTERESTING, desc = "enq1, (deq | enq2)"),
  new Outcome(id = Array("Some(y), List(x)"), expect = ACCEPTABLE_INTERESTING, desc = "enq2, (deq | enq1)"),
))
class MsQueueTest extends MsQueueStressTestBase {

  private[this] val queue =
    this.newQueue[String]()


  private[this] val tryDeque =
    queue.tryDeque

  @Actor
  def enq1(): Unit = {
    queue.enqueue("x").unsafePerform(null, this.impl)
  }

  @Actor
  def enq2(): Unit = {
    queue.enqueue("y").unsafePerform(null, this.impl)
  }

  @Actor
  def deq(r: LL_Result): Unit = {
    r.r1 = tryDeque.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = queue.drainOnce[SyncIO, String].unsafeRunSync()
  }
}
