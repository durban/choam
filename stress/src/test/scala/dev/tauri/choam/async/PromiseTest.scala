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

import java.util.concurrent.atomic.AtomicInteger

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLLL_Result

import cats.effect.{ IO, SyncIO }

@JCStressTest
@State
@Description("Promise: both getters should be unblocked")
@Outcomes(Array(
  new Outcome(id = Array("true, s, s, 1"), expect = ACCEPTABLE, desc = "get1 was faster"),
  new Outcome(id = Array("true, s, s, 2"), expect = ACCEPTABLE, desc = "get2 was faster")
))
class PromiseTest {

  val runtime =
    cats.effect.unsafe.IORuntime.global

  val p: Promise[String] =
    Promise[String].run[SyncIO].unsafeRunSync()

  val winner =
    new AtomicInteger(0)

  @Actor
  def complete(r: LLLL_Result): Unit = {
    r.r1 = this.p.tryComplete[IO]("s").unsafeRunSync()(this.runtime)
  }

  @Actor
  def get1(r: LLLL_Result): Unit = {
    r.r2 = this.p.get[IO].unsafeRunSync()(this.runtime)
    winner.compareAndSet(0, 1)
    ()
  }

  @Actor
  def get2(r: LLLL_Result): Unit = {
    r.r3 = this.p.get[IO].unsafeRunSync()(this.runtime)
    winner.compareAndSet(0, 2)
    ()
  }

  @Arbiter
  def arbiter(r: LLLL_Result): Unit = {
    r.r4 = winner.get()
  }
}
