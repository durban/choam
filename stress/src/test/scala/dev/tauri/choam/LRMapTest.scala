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
import org.openjdk.jcstress.infra.results.LLLLLL_Result

import kcas.Ref

@JCStressTest
@State
@Description("lmap and rmap must run before committing")
@Outcomes(Array(
  new Outcome(id = Array("a, x, a, x, ab, a"), expect = ACCEPTABLE, desc = "See old values")
))
class LRMapTest extends StressTestBase {

  private[this] val ref1: Ref[String] =
    Ref.mk("a")

  private[this] val ref2: Ref[String] =
    Ref.mk("x")

  private[this] def react1(r: LLLLLL_Result): React[Unit, String] = {
    ref1.modify(_ + "b").rmap { s =>
      r.r1 = ref1.unsafeInvisibleRead.unsafeRun(this.impl) // this is cheating
      r.r2 = ref2.unsafeInvisibleRead.unsafeRun(this.impl) // this is cheating
      s
    }
  }

  private[this] def react2(r: LLLLLL_Result): React[String, String] = React.computed { (s: String) =>
    ref2.modify(_ => s)
  }.lmap { (s: String) =>
    r.r3 = ref1.unsafeInvisibleRead.unsafeRun(this.impl) // this is cheating
    r.r4 = ref2.unsafeInvisibleRead.unsafeRun(this.impl) // this is cheating
    s
  }

  private[this] def react(r: LLLLLL_Result) =
    react1(r) >>> react2(r)

  @Actor
  def actor(r: LLLLLL_Result): Unit = {
    react(r).unsafeRun(this.impl)
    ()
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    val ctx = impl.currentContext()
    r.r5 = impl.read(ref1, ctx)
    r.r6 = impl.read(ref2, ctx)
  }
}
