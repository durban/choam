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

import core.Rxn

@JCStressTest
@State
@Description("RemoveQueue enq/deq should be composable")
@Outcomes(Array(
  new Outcome(id = Array("List(c, d), List(a, b)"), expect = ACCEPTABLE_INTERESTING, desc = "the only valid result")
))
class RemoveQueueComposedTest1 extends RemoveQueueStressTestBase {

  private[this] val queue1 = {
    val q = this.newQueue[String]()
    (for {
      //                0         2    3
      removers <- List("-", "a", "-", "-", "b", "c", "d").traverse { s =>
        q.enqueueWithRemover(s).run[SyncIO]
      }
      _ <- removers(0).run[SyncIO]
      _ <- removers(2).run[SyncIO]
      _ <- removers(3).run[SyncIO]
    } yield ()).unsafeRunSync()
    q
  }

  private[this] val queue2 =
    this.newQueue[String]()

  private[this] val tfer: Rxn[Unit] =
    queue1.tryDeque.map(_.getOrElse("x")).flatMap(queue2.enqueue)

  @Actor
  def transfer1(): Unit = {
    tfer.unsafePerform(this.impl)
  }

  @Actor
  def transfer2(): Unit = {
    tfer.unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r1 = queue1.drainOnce[SyncIO, String].unsafeRunSync()
    r.r2 = queue2.drainOnce[SyncIO, String].unsafeRunSync()
  }
}
