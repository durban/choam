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
import org.openjdk.jcstress.infra.results.LLLL_Result

import cats.effect.{ IO, SyncIO, Fiber }

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("AsyncQueue: cancelling enqueue must not lose items")
@Outcomes(Array(
  new Outcome(id = Array("true, x, completed, Some(a)"), expect = ACCEPTABLE, desc = "cancelled late"),
  new Outcome(id = Array("false, x, cancelled, None"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled"),
))
class AsyncQueueCancelEnqTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val q: BoundedQueue[String] = {
    val q = AsyncQueue.bounded[String](1).run[SyncIO].unsafeRunSync()
    assert(q.offer("x").run[SyncIO].unsafeRunSync()) // make it full
    q
  }

  private[this] var result: Boolean =
    false

  private[this] val offerer: Fiber[IO, Throwable, Unit] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.put[IO]("a")).flatTap { _ => IO { this.result = true } }
    }
    tsk.start.unsafeRunSync()(using runtime)
  }

  @Actor
  def take(r: LLLL_Result): Unit = {
    r.r2 = q.take[IO, String].unsafeRunSync()(using this.runtime)
  }

  @Actor
  def cancel(): Unit = {
    offerer.cancel.unsafeRunSync()(using runtime)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    val oc: String = offerer.join.flatMap { oc =>
      oc.fold(
        IO.pure("cancelled"),
        ex => IO.pure("error: " + ex.getClass().getName()),
        fa => fa.map { _ => "completed" },
      )
    }.unsafeRunSync()(using this.runtime)
    r.r1 = this.result
    r.r3 = oc
    r.r4 = q.poll.run[SyncIO].unsafeRunSync()
  }
}
