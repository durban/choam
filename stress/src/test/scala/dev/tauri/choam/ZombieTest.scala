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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

// @JCStressTest
@State
@Description("Can a running zombie Rxn see inconsistent values?")
@Outcomes(Array(
  new Outcome(id = Array("(a,a), (b,b), -"), expect = ACCEPTABLE, desc = "Read commits old values"),
  new Outcome(id = Array("(b,b), (b,b), -"), expect = ACCEPTABLE, desc = "Read commits new values"),
  new Outcome(id = Array("(a,a), (b,b), (a,b)"), expect = FORBIDDEN, desc = "Read cs. old values, but a zombie sees inconsistent"),
  new Outcome(id = Array("(a,a), (b,b), (b,a)"), expect = FORBIDDEN, desc = "Read cs. old values, but a zombie sees inconsistent"),
  new Outcome(id = Array("(b,b), (b,b), (b,a)"), expect = FORBIDDEN, desc = "Read cs. new values, but a zombie sees inconsistent"),
  new Outcome(id = Array("(b,b), (b,b), (a,b)"), expect = FORBIDDEN, desc = "Read cs. new values, but a zombie sees inconsistent"),
))
class ZombieTest extends StressTestBase {

  private[this] val ref1 =
    Ref.unsafe("a")

  private[this] val ref2 =
    Ref.unsafe("a")

  // a 2-CAS, setting both atomically "a" -> "b":
  private[this] val upd: Axn[Unit] =
    ref1.unsafeCas("a", "b") >>> ref2.unsafeCas("a", "b")

  // a consistent read of both:
  private[this] val _get: Axn[(String, String)] =
    ref1.get * ref2.get

  private[this] final def get(r: LLL_Result): Axn[(String, String)] = {
    this._get.map { result =>
      if (result._1 ne result._2) {
        // we've observed an inconsistent state
        // (we'll be retried, but we observed
        // an inconsistency nevertheless):
        r.r3 = result
      }
      result
    }
  }

  @Actor
  def update(): Unit = {
    upd.unsafeRun(this.impl)
  }

  @Actor
  def read(r: LLL_Result): Unit = {
    r.r1 = get(r).unsafeRun(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r2 = (ref1.get.unsafeRun(this.impl), ref2.get.unsafeRun(this.impl))
    if (r.r3 eq null) {
      // no inconsistency was observed
      r.r3 = "-"
    }
  }
}
