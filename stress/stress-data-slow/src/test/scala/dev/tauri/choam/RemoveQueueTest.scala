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

import cats.syntax.all._
import cats.effect.SyncIO

import data.{ Queue, RemoveQueue }

@JCStressTest
@State
@Description("RemoveQueue enq/deq should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("None, List(x, y)"), expect = ACCEPTABLE_INTERESTING, desc = "deq, enq1, enq2"),
  new Outcome(id = Array("None, List(y, x)"), expect = ACCEPTABLE_INTERESTING, desc = "deq, enq2, enq1"),
  new Outcome(id = Array("Some(x), List(y)"), expect = ACCEPTABLE, desc = "enq1, (deq | enq2)"),
  new Outcome(id = Array("Some(y), List(x)"), expect = ACCEPTABLE, desc = "enq2, (deq | enq1)"),
))
class RemoveQueueTest extends RemoveQueueStressTestBase {

  private[this] val queue: RemoveQueue[String] = {
    val q = this.newQueue[String]()
    (for {
      //                0    1
      removers <- List("-", "-").traverse { s =>
        q.enqueueWithRemover(s).run[SyncIO]
      }
      _ <- removers(0).run[SyncIO]
      _ <- removers(1).run[SyncIO]
    } yield ()).unsafeRunSync()
    q
  }

  private[this] val tryDeque =
    queue.poll

  @Actor
  def enq1(): Unit = {
    queue.add("x").unsafePerform(this.impl)
  }

  @Actor
  def enq2(): Unit = {
    queue.add("y").unsafePerform(this.impl)
  }

  @Actor
  def deq(r: LL_Result): Unit = {
    r.r1 = tryDeque.unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = Queue.drainOnce[SyncIO, String](queue).unsafeRunSync()
  }
}
