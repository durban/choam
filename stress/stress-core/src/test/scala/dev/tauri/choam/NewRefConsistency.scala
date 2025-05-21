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
import org.openjdk.jcstress.infra.results.LL_Result

import core.{ Rxn, Axn, Ref }

@JCStressTest
@State
@Description("New Ref must be consistent with previously read value")
@Outcomes(Array(
  new Outcome(id = Array("ab, x"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class NewRefConsistency extends StressTestBase {

  private[this] val holder: Ref[Ref[String]] =
    Ref[Ref[String]](null).unsafePerform(null, this.impl)

  private[this] val existingRef: Ref[String] =
    Ref("a").unsafePerform(null, this.impl)

  private[this] val _createNewRef: Axn[Unit] =
    existingRef.update(_ + "b") *> Ref("x") >>> holder.set0

  private[this] val _readNewRef: Axn[(String, String)] = {
    existingRef.get * holder.get.flatMapF {
      case null => Rxn.unsafe.retry
      case newRef => newRef.get
    }
  }

  @Actor
  def createNewRef(): Unit = {
    this._createNewRef.unsafePerform(null, this.impl)
  }

  @Actor
  def rxn2(r: LL_Result): Unit = {
    val res = this._readNewRef.unsafePerform(null, this.impl)
    r.r1 = res._1
    r.r2 = res._2
  }
}
