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

import cats.syntax.all._
import cats.effect.{ IO, SyncIO, Fiber }

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("AsyncQueue: it must be linearizable (cancelling dequeue)")
@Outcomes(Array(
  new Outcome(id = Array("a, b, None, completed: a"), expect = ACCEPTABLE, desc = "cancelled late"),
  new Outcome(id = Array("null, a, Some(b), cancelled"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled"),
  new Outcome(id = Array("null, b, Some(a), cancelled"), expect = FORBIDDEN, desc = "cancelled, but items swapped"),
))
class AsyncQueueLinearizableTest {

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
  def offer2(): Unit = {
    (q.enqueueAsync[IO]("a") *> q.enqueueAsync[IO]("b")).unsafeRunSync()(using this.runtime)
  }

  @Actor
  def cancel(): Unit = {
    taker.cancel.unsafeRunSync()(using runtime)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    val ocAndResult2 = (taker.join, q.deque[IO, String]).flatMapN { (oc, result2) =>
      oc.fold(
        IO.pure("cancelled"),
        ex => IO.pure("error: " + ex.getClass().getName()),
        fa => fa.map { result => s"completed: $result" },
      ).map { oc => (oc, result2) }
    }.unsafeRunSync()(using this.runtime)
    val oc: String = ocAndResult2._1
    val result2: String = ocAndResult2._2
    r.r1 = this.result
    r.r2 = result2
    r.r3 = q.tryDeque.run[SyncIO].unsafeRunSync()
    r.r4 = oc
  }
}
