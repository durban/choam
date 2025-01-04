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

import cats.effect.kernel.Fiber
import cats.effect.IO
import cats.effect.std.Queue

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LLL_Result

// @JCStressTest
@State
@Description("cats.effect.std.Queue")
@Outcomes(Array(
  new Outcome(id = Array("Succeeded, foo, None"), expect = ACCEPTABLE, desc = "succ"),
  new Outcome(id = Array("Canceled, null, Some(foo)"), expect = ACCEPTABLE_INTERESTING, desc = "cancel"),
  new Outcome(id = Array("Errored, .*, .*"), expect = FORBIDDEN, desc = "err"),
))
class CatsQueueTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val q: Queue[IO, String] =
    Queue.bounded[IO, String](capacity = 64).unsafeRunSync()(this.runtime)

  private[this] var taken: String =
    null

  private[this] val taker: Fiber[IO, Throwable, String] = {
    val tsk = IO.uncancelable { poll =>
      poll(q.take).flatTap { taken =>
        IO { this.taken = taken }
      }
    }
    tsk.start.unsafeRunSync()(this.runtime)
  }

  @Actor
  def canceller(): Unit = {
    taker.cancel.unsafeRunSync()(this.runtime)
  }

  @Actor
  def offerer(): Unit = {
    q.offer("foo").unsafeRunSync()(this.runtime)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r1 = taker.join.unsafeRunSync()(this.runtime).productPrefix : String
    r.r2 = this.taken
    r.r3 = q.tryTake.unsafeRunSync()(this.runtime)
  }
}
