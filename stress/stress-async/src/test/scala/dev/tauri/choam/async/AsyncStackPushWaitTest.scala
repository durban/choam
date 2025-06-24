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

import org.openjdk.jcstress.annotations.{ Outcome => JcsOutcome, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLL_Result

import cats.effect.{ IO, SyncIO, Fiber }
import cats.effect.kernel.Outcome

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("AsyncStack: racing pushes should work fine with waiting pop")
@Outcomes(Array(
  new JcsOutcome(id = Array("completed: b, b, Some(a), None"), expect = ACCEPTABLE, desc = "completed, seen b"),
  new JcsOutcome(id = Array("completed: a, a, Some(b), None"), expect = ACCEPTABLE, desc = "completed, seen a"),
  new JcsOutcome(id = Array("cancelled, null, Some(b), Some(a)"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled, ba"),
  new JcsOutcome(id = Array("cancelled, null, Some(a), Some(b)"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled, ab"),
))
class AsyncStackPushWaitTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val stack: AsyncStack[String] =
    AsyncStack.treiberStack[String].run[SyncIO].unsafeRunSync()

  private[this] var popResult: String =
    null

  private[this] val popper: Fiber[IO, Throwable, String] = {
    val popTask = IO.uncancelable { poll=>
      poll(stack.pop).flatTap { s =>
        IO { popResult = s }
      }
    }
    popTask.start.unsafeRunSync()(using runtime)
  }

  @Actor
  def push1(): Unit = {
    (stack.push[IO]("a") <* popper.cancel).unsafeRunSync()(using this.runtime)
  }

  @Actor
  def push2(): Unit = {
    (stack.push[IO]("b") <* popper.cancel).unsafeRunSync()(using this.runtime)
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    val oc: String = this.popper.join.flatMap {
      case Outcome.Canceled() => IO.pure("cancelled")
      case Outcome.Errored(e) => IO.pure("errored")
      case Outcome.Succeeded(fa) => fa.map { r => s"completed: ${r}" }
    }.unsafeRunSync()(using this.runtime)
    r.r1 = oc
    r.r2 = this.popResult
    r.r3 = this.stack.tryPop.run[IO].unsafeRunSync()(using this.runtime)
    r.r4 = this.stack.tryPop.run[IO].unsafeRunSync()(using this.runtime)
  }
}
