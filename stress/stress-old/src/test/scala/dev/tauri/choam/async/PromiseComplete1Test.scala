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
import org.openjdk.jcstress.infra.results.ZZL_Result

import cats.effect.{ IO, SyncIO, Fiber }

import ce.unsafeImplicits._

@JCStressTest
@State
@Description("Promise: racing completers should work fine")
@Outcomes(Array(
  new Outcome(id = Array("true, false, 1"), expect = ACCEPTABLE_INTERESTING, desc = "complete1 was faster"),
  new Outcome(id = Array("false, true, 2"), expect = ACCEPTABLE_INTERESTING, desc = "complete2 was faster"),
))
class PromiseComplete1Test {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val p: Promise[String] =
    Promise[String].run[SyncIO].unsafeRunSync()

  private[this] val getter: Fiber[IO, Throwable, String] =
    this.p.get.start.unsafeRunSync()(using this.runtime)

  @Actor
  def complete1(r: ZZL_Result): Unit = {
    r.r1 = this.p.complete0[IO]("1").unsafeRunSync()(using this.runtime)
  }

  @Actor
  def complete2(r: ZZL_Result): Unit = {
    r.r2 = this.p.complete0[IO]("2").unsafeRunSync()(using this.runtime)
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    r.r3 = getter.joinWithNever.unsafeRunSync()(using runtime)
  }
}
