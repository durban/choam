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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

import core.{ Rxn, Ref }

@JCStressTest
@State
@Description("Can a running zombie Rxn see inconsistent values?")
@Outcomes(Array(
  new Outcome(id = Array("a, b, -"), expect = ACCEPTABLE_INTERESTING, desc = "No inconsistency observed"),
  new Outcome(id = Array("a, b, a"), expect = FORBIDDEN, desc = "Inconsistency observed"),
))
class ZombieTestSwap extends StressTestBase {

  private[this] val ref1 =
    Ref.unsafe("a", AllocationStrategy.Padded, this.rig)

  private[this] val ref2 =
    Ref.unsafe("b", AllocationStrategy.Padded, this.rig)

  // atomically swap the contents of the refs:
  private[this] val swap: Rxn[Unit] =
    Ref.swap(ref1, ref2)

  // another swap, but checks the observed values:
  private[this] final def swapObserve(r: LLL_Result): Rxn[Unit] = {
    ref1.get.flatMap { o1 =>
      ref2.modify[String] { o2 =>
        if (o1 eq o2) {
          // we've observed an inconsistent state
          // (we'll be retried, but we observed
          // an inconsistency nevertheless):
          r.r3 = o1
        }
        (o1, o2)
      }.flatMap(ref1.set)
    }
  }

  @Actor
  def swap1(): Unit = {
    swap.unsafePerform(this.impl)
  }

  @Actor
  def swap2(r: LLL_Result): Unit = {
    swapObserve(r).unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r1 = ref1.get.unsafePerform(this.impl)
    r.r2 = ref2.get.unsafePerform(this.impl)
    if (r.r3 eq null) {
      // no inconsistency was observed
      r.r3 = "-"
    }
  }
}
