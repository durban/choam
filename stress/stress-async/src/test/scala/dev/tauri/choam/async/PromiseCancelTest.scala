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
import org.openjdk.jcstress.infra.results.ZLL_Result

import cats.effect.{ IO, SyncIO, Fiber }

@JCStressTest
@State
@Description("Promise: get should be cancellable")
@Outcomes(Array(
  new Outcome(id = Array("true, null, null"), expect = ACCEPTABLE, desc = "both cancel were faster"),
  new Outcome(id = Array("true, null, s"), expect = ACCEPTABLE, desc = "cancel1 was faster"),
  new Outcome(id = Array("true, s, null"), expect = ACCEPTABLE, desc = "cancel2 was faster"),
  new Outcome(id = Array("true, s, s"), expect = ACCEPTABLE, desc = "complete was faster")
))
class PromiseCancelTest extends StressTestBase {

  private[this] val runtime =
    cats.effect.unsafe.IORuntime.global

  private[this] val p: Promise[String] =
    Promise[String].run[SyncIO].unsafeRunSync()

  private[this] var result1: String =
    null

  private[this] var result2: String =
    null

  private[this] val getter1: Fiber[IO, Throwable, String] =
    IO.uncancelable(poll => poll(p.get).flatTap { s => IO { result1 = s } }).start.unsafeRunSync()(using runtime)

  private[this] val getter2: Fiber[IO, Throwable, String] =
    IO.uncancelable(poll => poll(p.get).flatTap { s => IO { result2 = s } }).start.unsafeRunSync()(using runtime)

  @Actor
  def complete(r: ZLL_Result): Unit = {
    r.r1 = this.p.complete("s").run[SyncIO].unsafeRunSync()
  }

  @Actor
  def cancel(): Unit = {
    IO.both(getter1.cancel, getter2.cancel).void.unsafeRunSync()(using this.runtime)
  }

  @Arbiter
  def arbiter(r: ZLL_Result): Unit = {
    getter1.join.unsafeRunSync()(using this.runtime)
    r.r2 = this.result1
    getter2.join.unsafeRunSync()(using this.runtime)
    r.r3 = this.result2
  }
}
