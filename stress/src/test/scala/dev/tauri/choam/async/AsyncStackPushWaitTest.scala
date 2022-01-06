/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
import org.openjdk.jcstress.infra.results.LL_Result

import cats.effect.{ IO, SyncIO, Fiber }

// @JCStressTest
@State
@Description("AsyncStack: racing pushes should work fine with waiting pop")
@Outcomes(Array(
  new Outcome(id = Array("a, b"), expect = ACCEPTABLE, desc = "push1 was faster"),
  new Outcome(id = Array("b, a"), expect = ACCEPTABLE, desc = "push2 was faster")
))
class AsyncStackPushWaitTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val stack: AsyncStack[IO, String] =
    AsyncStack[IO, String].run[SyncIO].unsafeRunSync()

  private[this] val popper: Fiber[IO, Throwable, String] =
    stack.pop.start.unsafeRunSync()(runtime)

  @Actor
  def push1(): Unit = {
    stack.push[IO]("a").unsafeRunSync()(this.runtime)
  }

  @Actor
  def push2(): Unit = {
    stack.push[IO]("b").unsafeRunSync()(this.runtime)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r1 = this.popper.joinWithNever.unsafeRunSync()(this.runtime)
    r.r2 = this.stack.pop.unsafeRunSync()(this.runtime)
  }
}
