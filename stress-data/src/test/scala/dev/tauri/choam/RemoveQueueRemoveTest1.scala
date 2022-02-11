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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLZ_Result

import cats.effect.SyncIO

import data.Queue

@JCStressTest
@State
@Description("RemoveQueue concurrent deq and remove")
@Outcomes(Array(
  new Outcome(id = Array("List(), Some(z), false"), expect = ACCEPTABLE_INTERESTING, desc = "deq wins"),
  new Outcome(id = Array("List(), None, true"), expect = ACCEPTABLE, desc = "rem wins"),
  new Outcome(id = Array("List(), Some(z), true"), expect = FORBIDDEN, desc = "rem seems to win, but doesn't")
))
class RemoveQueueRemoveTest1 extends RemoveQueueStressTestBase {

  private[this] val queue: Queue.WithRemove[String] = {
    this.newQueue("z")
  }

  private[this] val tryDeque =
    queue.tryDeque

  private[this] val remove =
    queue.remove

  @Actor
  def deq(r: LLZ_Result): Unit = {
    r.r2 = tryDeque.unsafeRun(this.impl)
  }

  @Actor
  def rem(r: LLZ_Result): Unit = {
    r.r3 = remove.unsafePerform("z", this.impl)
  }

  @Arbiter
  def arbiter(r: LLZ_Result): Unit = {
    r.r1 = queue.drainOnce[SyncIO, String].unsafeRunSync()
  }
}
