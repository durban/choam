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
package internal
package mcas

import org.scalacheck.{ Gen, Arbitrary, Prop }
import org.scalacheck.util.Buildable
import org.scalacheck.Prop.forAll

trait PropertyHelpers {

  private def genLongWithRig(implicit arb: Arbitrary[Int]): Gen[Long] = {
    arb.arbitrary.map { n =>
      RefIdGen.compute(base = java.lang.Long.MIN_VALUE, offset = n)
    }
  }

  protected def myForAll(body: (Long, Set[Long]) => Prop): Prop = {
    val genSeed = Gen.choose[Long](Long.MinValue, Long.MaxValue)
    val arbLongWithRig = Arbitrary { genLongWithRig }
    val genNums = Arbitrary.arbContainer[Set, Long](using
      arbLongWithRig,
      Buildable.buildableFactory,
      c => c
    ).arbitrary
    forAll(genSeed, genNums)(body)
  }
}
