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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

import kcas._

@JCStressTest
@State
@Description("Ref#getter should be consistent")
@Outcomes(Array(
  new Outcome(id = Array("(foo,bar), (x,y)"), expect = ACCEPTABLE, desc = "Read old values"),
  new Outcome(id = Array("(x,y), (x,y)"), expect = ACCEPTABLE, desc = "Read new values")
))
class ConsistentReadTest extends StressTestBase {

  private[this] var ref1 =
    Ref.unsafe("foo")

  private[this] var ref2 =
    Ref.unsafe("bar")

  private[this] var upd: React[Unit, Unit] =
    ref1.unsafeCas("foo", "x") >>> ref2.unsafeCas("bar", "y")

  private[this] var get: React[Unit, (String, String)] =
    ref1.getter * ref2.getter

  @Actor
  def update(): Unit = {
    upd.unsafeRun(this.impl)
  }

  @Actor
  def read(r: LL_Result): Unit = {
    r.r1 = get.unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LL_Result): Unit = {
    r.r2 = (ref1.getter.unsafeRun(this.impl), ref2.getter.unsafeRun(this.impl))
  }
}
