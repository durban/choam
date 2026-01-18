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
@Description("Write after a .get")
@Outcomes(Array(
  new Outcome(id = Array("2, a, 1"), expect = ACCEPTABLE_INTERESTING, desc = "writer2 first"),
  new Outcome(id = Array("a, 1, 2"), expect = ACCEPTABLE_INTERESTING, desc = "writer1 first"),
))
class WriteAfterGet extends StressTestBase {

  private[this] val ref: Ref[String] =
    Ref.unsafe("a", AllocationStrategy.Padded, this.rig)

  private[this] val write1: Rxn[String] = {
    ref.get.flatMap { old1 =>
      ref.modify { old2 =>
        if (old1 eq old2) ("1", old1)
        else (old2, "ERR")
      }
    }
  }

  private[this] val write2: Rxn[String] = {
    ref.get.flatMap { old1 =>
      ref.modify { old2 =>
        if (old1 eq old2) ("2", old1)
        else (old2, "ERR")
      }
    }
  }

  @Actor
  def writer1(r: LLL_Result): Unit = {
    r.r1 = this.write1.unsafePerform(this.impl)
  }

  @Actor
  def writer2(r: LLL_Result): Unit = {
    r.r2 = this.write2.unsafePerform(this.impl)
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = this.impl.currentContext().readDirect(this.ref.loc)
  }
}
