/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

@JCStressTest
@State
@Description("AsyncStack1: racing waiting pops should work fine")
@Outcomes(Array(
  new Outcome(id = Array("a, b"), expect = ACCEPTABLE, desc = "pop1 was faster"),
  new Outcome(id = Array("b, a"), expect = ACCEPTABLE, desc = "pop2 was faster")
))
class AsyncStack1PopWaitTest {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val stack: AsyncStack[IO, String] =
    AsyncStack.impl1[IO, String].run[SyncIO].unsafeRunSync()

  private[this] val popper1: Fiber[IO, Throwable, String] =
    stack.pop.start.unsafeRunSync()(runtime)

  private[this] val popper2: Fiber[IO, Throwable, String] =
    stack.pop.start.unsafeRunSync()(runtime)

  @Actor
  def push(): Unit = {
    (stack.push[IO]("a") >> stack.push[IO]("b")).unsafeRunSync()(this.runtime)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r1 = this.popper1.joinWithNever.unsafeRunSync()(this.runtime)
    r.r2 = this.popper2.joinWithNever.unsafeRunSync()(this.runtime)
  }
}
