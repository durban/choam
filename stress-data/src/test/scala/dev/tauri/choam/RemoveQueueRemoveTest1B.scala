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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import cats.effect.SyncIO

import data.Queue
import ce._

@JCStressTest
@State
@Description("RemoveQueue concurrent deq and remove (with remover)")
@Outcomes(Array(
  new Outcome(id = Array("List(x), Some(z)"), expect = ACCEPTABLE_INTERESTING, desc = "deq wins"),
  new Outcome(id = Array("List(), Some(x)"), expect = ACCEPTABLE, desc = "rem wins"),
))
class RemoveQueueRemoveTest1B extends RemoveQueueStressTestBase {

  private[this] val queueAndRemover = {
    val q = this.newQueue[String]()
    val remover = q.enqueueWithRemover.unsafePerform("z", this.impl)
    q.enqueue.unsafePerform("x", this.impl)
    (q, remover)
  }

  private[this] val queue: Queue.WithRemove[String] =
    queueAndRemover._1

  private[this] val remover: Axn[Unit] =
    queueAndRemover._2

  private[this] val tryDeque =
    queue.tryDeque

  @Actor
  def deq(r: LL_Result): Unit = {
    r.r2 = tryDeque.unsafeRun(this.impl)
  }

  @Actor
  def rem(): Unit = {
    remover.unsafePerform(null : Any, this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r1 = queue.drainOnce[SyncIO, String].unsafeRunSync()
  }
}
