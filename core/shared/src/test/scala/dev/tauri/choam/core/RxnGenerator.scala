/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package core

import java.lang.Math

import org.scalacheck.Gen
import org.scalacheck.rng.Seed

import internal.mcas.{ Mcas, RefIdGen }

final class RxnGenerator(mcas: Mcas) { self =>

  private[this] val params = Gen.Parameters.default

  private[this] val ti: TestInstancesCore = new TestInstancesCore {

    override def mcasImpl: Mcas =
      self.mcas

    override protected def rigInstance: RefIdGen =
      this.mcasImpl.currentContext().refIdGen
  }

  final def generate(seed: Long): Rxn[Any] = {
    val nxt = Seed(seed).next
    val gen = Math.abs(seed.toInt % 4) match {
      case 0 => ti.arbAxn[Int]
      case 1 => ti.arbAxn[String]
      case 2 => ti.arbAxn[Long]
      case 3 => ti.arbAxn[Any](using ti.arbAny)
      case x => impossible(s"abs(_ % 4) returned ${x}")
    }
    gen.arbitrary.pureApply(params, nxt)
  }
}

object RxnGenerator {
  // TODO: TestException
}
