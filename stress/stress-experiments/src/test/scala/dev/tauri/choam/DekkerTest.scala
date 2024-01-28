/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicBoolean

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.I_Result

@JCStressTest
@State
@Description("Dekker")
@Outcomes(Array(
  new JOutcome(id = Array("2"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class DekkerTest {

  private[this] val wantsToEnter0 =
    new AtomicBoolean

  private[this] val wantsToEnter1 =
    new AtomicBoolean

  private[this] val turn =
    new AtomicBoolean

  @volatile
  private[this] var ctr =
    0

  @Actor
  def p0(): Unit = {
    // ACQUIRE:
    wantsToEnter0.set(true)
    while (wantsToEnter1.get()) {
      if (turn.getAcquire()) {
        wantsToEnter0.set(false)
        while (turn.getAcquire()) {
          Thread.onSpinWait()
        }
        wantsToEnter0.set(true)
      }
    }

    // CRITICAL SECTION:
    ctr = ctr + 1

    // RELEASE:
    turn.setRelease(true)
    wantsToEnter0.set(false)
  }

  @Actor
  def p1(): Unit = {
    // ACQUIRE:
    wantsToEnter1.set(true)
    while (wantsToEnter0.get()) {
      if (!turn.getAcquire()) {
        wantsToEnter1.set(false)
        while (!turn.getAcquire()) {
          Thread.onSpinWait()
        }
        wantsToEnter1.set(true)
      }
    }

    // CRITICAL SECTION:
    ctr = ctr + 1

    // RELEASE:
    turn.setRelease(false)
    wantsToEnter1.set(false)
  }

  @Arbiter
  def arbiter(r: I_Result): Unit = {
    r.r1 = ctr
  }
}
