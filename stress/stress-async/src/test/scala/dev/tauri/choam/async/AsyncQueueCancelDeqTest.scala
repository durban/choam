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

import java.util.concurrent.CountDownLatch

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLLL_Result

import cats.effect.{ IO, SyncIO, Fiber }

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("AsyncQueue: cancelling dequeue must not lose items")
@Outcomes(Array(
  new Outcome(id = Array("a, b, c, completed1: a, completed2: b"), expect = ACCEPTABLE, desc = "cancelled late"),
  new Outcome(id = Array("null, a, b, cancelled1, completed2: a"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled"),
  // this also happens:   b, a, c, completed1: b, completed2: a // <- TODO: is this a bug, or just taker2 subscribing first?
))
class AsyncQueueCancelDeqTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val q: UnboundedQueue[String] =
    AsyncQueue.unbounded[String].run[SyncIO].unsafeRunSync()

  private[this] var result1: String =
    null

  private[this] var result2: String =
    null

  private[this] var latch1: CountDownLatch =
    new CountDownLatch(1)

  private[this] val taker1: Fiber[IO, Throwable, String] = {
    val latch2 = new CountDownLatch(1)
    val tsk = IO.uncancelable { poll =>
      latch1.countDown()
      latch2.countDown()
      poll(q.deque[IO, String]).flatTap { s => IO { this.result1 = s } }
    }
    val fib = tsk.start.unsafeRunSync()(using runtime)
    latch2.await()
    fib
  }

  private[this] val taker2: Fiber[IO, Throwable, String] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.deque[IO, String]).flatTap { s => IO { this.result2 = s } }
    }
    latch1.await()
    latch1 = null
    Thread.`yield`()
    val fib = tsk.start.unsafeRunSync()(using runtime)
    fib
  }

  @Actor
  def offer(): Unit = {
    (q.enqueue[IO]("a") *> q.enqueue[IO]("b")).unsafeRunSync()(using this.runtime)
  }

  @Actor
  def cancel(): Unit = {
    taker1.cancel.unsafeRunSync()(using runtime)
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    q.enqueue[IO]("c").unsafeRunSync()(using this.runtime)
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
    r.r3 = q.deque[IO, String].unsafeRunSync()(using this.runtime)
    r.r4 = oc1
    r.r5 = oc2
  }
}
