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

import cats.effect.SyncIO
import cats.syntax.all._

import core.Rxn

@JCStressTest
@State
@Description("Promise: completing 2 promises should occur atomically")
@Outcomes(Array(
  new Outcome(id = Array("true, (None,None), (Some(x),Some(y))"), expect = ACCEPTABLE, desc = "get wins"),
  new Outcome(id = Array("true, (Some(x),Some(y)), (Some(x),Some(y))"), expect = ACCEPTABLE_INTERESTING, desc = "complete wins"),
  new Outcome(id = Array("true, (None,Some(y)), (Some(x),Some(y))"), expect = ACCEPTABLE_INTERESTING, desc = "complete between the 2 reads"),
))
class PromiseCompleteTest extends StressTestBase {

  private[this] val p1: Promise[String] =
    Promise[String].run[SyncIO].unsafeRunSync()

  private[this] val p2: Promise[String] =
    Promise[String].run[SyncIO].unsafeRunSync()

  private[this] final def completeBoth(s1: String, s2: String): Rxn[(Boolean, Boolean)] =
    p1.complete(s1) * p2.complete(s2)

  private[this] val tryGetBoth: SyncIO[(Option[String], Option[String])] =
    (p1.tryGet.run[SyncIO], p2.tryGet.run[SyncIO]).tupled

  @Actor
  def complete(r: ZLL_Result): Unit = {
    val res = completeBoth("x", "y").run[SyncIO].unsafeRunSync()
    r.r1 = res._1 && res._2
  }

  @Actor
  def get(r: ZLL_Result): Unit = {
    r.r2 = tryGetBoth.unsafeRunSync()
  }

  @Arbiter
  def arbiter(r: ZLL_Result): Unit = {
    r.r3 = tryGetBoth.unsafeRunSync()
  }
}
