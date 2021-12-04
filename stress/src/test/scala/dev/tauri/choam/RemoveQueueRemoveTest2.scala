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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LZ_Result

import cats.effect.SyncIO

import data.Queue

@JCStressTest
@State
@Description("RemoveQueue concurrent enq and remove")
@Outcomes(Array(
  new Outcome(id = Array("List(), true"), expect = ACCEPTABLE, desc = "enq wins"),
  new Outcome(id = Array("List(x), false"), expect = ACCEPTABLE, desc = "rem wins")
))
class RemoveQueueRemoveTest2 extends RemoveQueueStressTestBase {

  private[this] val queue: Queue.WithRemove[String] = {
    this.newQueue()
  }

  private[this] val enqueue =
    queue.enqueue

  private[this] val remove =
    queue.remove

  @Actor
  def enq(): Unit = {
    enqueue.unsafePerform("x", this.impl)
  }

  @Actor
  def rem(r: LZ_Result): Unit = {
    r.r2 = remove.unsafePerform("x", this.impl)
  }

  @Arbiter
  def arbiter(r: LZ_Result): Unit = {
    r.r1 = queue.drainOnce[SyncIO, String].unsafeRunSync()
  }
}
