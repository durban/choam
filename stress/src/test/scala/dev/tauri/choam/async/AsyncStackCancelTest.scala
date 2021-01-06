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

import scala.annotation.unused

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import cats.effect.{ IO, SyncIO, Fiber }
import cats.syntax.all._

@JCStressTest
@State
@Description("AsyncStack: cancelling pop must not lose items")
@Outcomes(Array(
  new Outcome(id = Array("a, b"), expect = ACCEPTABLE, desc = "cancelled late"),
  new Outcome(id = Array("null, a"), expect = ACCEPTABLE, desc = "cancelled"),
  new Outcome(id = Array("null, b"), expect = ACCEPTABLE_INTERESTING, desc = "cancelled just after completing the Promise"),
))
class AsyncStackCancelTest {

  val runtime =
    cats.effect.unsafe.IORuntime.global

  val stack: AsyncStack[String] =
    AsyncStack[String].run[SyncIO].unsafeRunSync()

  var result: String =
    null

  val popper: Fiber[IO, Throwable, String] =
    stack.pop[IO].flatTap { s => IO { this.result = s } }.start.unsafeRunSync()(runtime)

  @Actor
  def push(@unused r: LL_Result): Unit = {
    (stack.push[IO]("a") >> stack.push[IO]("b")).unsafeRunSync()(this.runtime)
  }

  @Actor
  def cancel(@unused r: LL_Result): Unit = {
    popper.cancel.unsafeRunSync()(runtime)
  }

  @Actor
  def pop(r: LL_Result): Unit = {
    (stack.pop[IO].flatMap { s => IO { r.r2 = s } }).unsafeRunSync()(runtime)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    popper.join.unsafeRunSync()(this.runtime)
    r.r1 = this.result
  }
}
