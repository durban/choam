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
import org.openjdk.jcstress.infra.results.LLLLLL_Result

import cats.effect.{ IO, SyncIO, Fiber }

@JCStressTest
@State
@Description("AsyncQueue: cancelling dequeue must not lose items")
@Outcomes(Array(
  new Outcome(id = Array("null, a, Some(b), cancelled1, completed2: a, None"), expect = ACCEPTABLE, desc = "cancelled1"),
  new Outcome(id = Array("a, null, Some(b), completed1: a, cancelled2, None"), expect = ACCEPTABLE, desc = "cancelled2"),
  new Outcome(id = Array("null, null, Some(a), cancelled1, cancelled2, Some(b)"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled both"),
))
class AsyncQueueCancelDeqTest extends StressTestBase {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val q: AsyncQueue[String] =
    AsyncQueue.unbounded[String].run[SyncIO].unsafeRunSync()

  private[this] var result1: String =
    null

  private[this] var result2: String =
    null

  // Note: we don't know the order of `taker1` and `taker2`;
  // it is possible that `taker2` subscribes first.

  private[this] val taker1: Fiber[IO, Throwable, String] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.take[IO, String]).flatTap { s => IO { this.result1 = s } }
    }
    tsk.start.unsafeRunSync()(using runtime)
  }

  private[this] val taker2: Fiber[IO, Throwable, String] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.take[IO, String]).flatTap { s => IO { this.result2 = s } }
    }
    tsk.start.unsafeRunSync()(using runtime)
  }

  @Actor
  def offer(): Unit = {
    q.put[IO]("a").unsafeRunSync()(using this.runtime)
  }

  @Actor
  def cancel(): Unit = {
    (taker1.cancel *> taker2.cancel).unsafeRunSync()(using runtime)
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    q.put[IO]("b").unsafeRunSync()(using this.runtime)
    val oc1: String = taker1.join.flatMap { oc =>
      oc.fold(
        IO.pure("cancelled1"),
        ex => IO.pure("error1: " + ex.getClass().getName()),
        fa => fa.map { result => s"completed1: $result" },
      )
    }.unsafeRunSync()(using this.runtime)
    val oc2: String = taker2.join.flatMap { oc =>
      oc.fold(
        IO.pure("cancelled2"),
        ex => IO.pure("error2: " + ex.getClass().getName()),
        fa => fa.map { result => s"completed2: $result" },
      )
    }.unsafeRunSync()(using this.runtime)
    r.r1 = this.result1
    r.r2 = this.result2
    r.r3 = q.poll.run[IO].unsafeRunSync()(using this.runtime)
    r.r4 = oc1
    r.r5 = oc2
    r.r6 = q.poll.run[IO].unsafeRunSync()(using this.runtime)
  }
}
