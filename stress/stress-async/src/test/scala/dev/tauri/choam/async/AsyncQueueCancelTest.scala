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
package async

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

import cats.effect.{ IO, SyncIO, Fiber }

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("AsyncQueue: cancelling dequeue must not lose items")
@Outcomes(Array(
  new Outcome(id = Array("a, b, completed: a"), expect = ACCEPTABLE, desc = "cancelled late"),
  new Outcome(id = Array("null, a, cancelled"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled"),
  new Outcome(id = Array("null, b, .*"), expect = FORBIDDEN, desc = "cancelled, item 'a' lost"),
))
class AsyncQueueCancelTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val q: UnboundedQueue[String] =
    AsyncQueue.unbounded[String].run[SyncIO].unsafeRunSync()

  private[this] var result: String =
    null

  private[this] val taker: Fiber[IO, Throwable, String] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.deque[IO, String]).flatTap { s => IO { this.result = s } }
    }
    tsk.start.unsafeRunSync()(using runtime)
  }

  @Actor
  def offer(): Unit = {
    q.enqueue[IO]("a").unsafeRunSync()(using this.runtime)
  }

  @Actor
  def cancel(): Unit = {
    taker.cancel.unsafeRunSync()(using runtime)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    val oc: String = taker.join.flatMap { oc =>
      oc.fold(
        IO.pure("cancelled"),
        ex => IO.pure("error: " + ex.getClass().getName()),
        fa => fa.map { result => s"completed: $result" },
      )
    }.unsafeRunSync()(using this.runtime)
    r.r1 = this.result
    q.enqueue[IO]("b").unsafeRunSync()(using this.runtime)
    r.r2 = q.deque[IO, String].unsafeRunSync()(using this.runtime)
    r.r3 = oc
  }
}
