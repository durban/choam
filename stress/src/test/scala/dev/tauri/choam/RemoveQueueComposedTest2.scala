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
import org.openjdk.jcstress.infra.results.LL_Result

import cats.effect.SyncIO

@JCStressTest
@State
@Description("RemoveQueue enq/deq should be composable (concurrent enqueue)")
@Outcomes(Array(
  new Outcome(id = Array("List(b, c, d), List(y, a)"), expect = ACCEPTABLE, desc = "Additional enq is first"),
  new Outcome(id = Array("List(b, c, d), List(a, y)"), expect = ACCEPTABLE, desc = "Additional enq is last")
))
class RemoveQueueComposedTest2 extends RemoveQueueStressTestBase {

  private[this] val queue1 = {
    val q = this.newQueue("-", "a", "-", "-", "b", "c", "d")
    val lst = (for {
      _ <- q.remove[SyncIO]("-")
      _ <- q.remove[SyncIO]("-")
      _ <- q.remove[SyncIO]("-")
      lst <- q.unsafeToList[SyncIO]
    } yield lst).unsafeRunSync()
    assert(lst == List("a", "b", "c", "d"))
    q
  }

  private[this] val queue2 =
    this.newQueue[String]()

  private[this] val tfer: Reaction[Unit, Unit] =
    queue1.tryDeque.map(_.getOrElse("x")) >>> queue2.enqueue

  @Actor
  def transfer(): Unit = {
    tfer.unsafePerform((), this.impl)
  }

  @Actor
  def concurrentEnqueue(): Unit = {
    queue2.enqueue.unsafePerform("y", this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r1 = queue1.unsafeToList[SyncIO].unsafeRunSync()
    r.r2 = queue2.unsafeToList[SyncIO].unsafeRunSync()
  }
}
