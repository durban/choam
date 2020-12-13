/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.annotation.unused

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLZ_Result

import kcas.{ KCAS, Ref }

@KCASParams("Invisible read may see intermediate values, but not descriptors")
@Outcomes(Array(
  new Outcome(id = Array("a, x, true"), expect = ACCEPTABLE, desc = "Sees old values"),
  new Outcome(id = Array("b, x, true"), expect = ACCEPTABLE_INTERESTING, desc = "Sees new ref1"),
  new Outcome(id = Array("a, y, true"), expect = ACCEPTABLE_INTERESTING, desc = "Sees new ref2"),
  new Outcome(id = Array("b, y, true"), expect = ACCEPTABLE, desc = "Sees new values")
))
abstract class InvisibleReadTest(impl: KCAS) {

  private[this] val ref1: Ref[String] =
    Ref.mk("a")

  private[this] val ref2: Ref[String] =
    Ref.mk("x")

  private[this] val read1: React[Unit, String] =
    React.invisibleRead(ref1)

  private[this] val read2: React[Unit, String] =
    React.invisibleRead(ref2)

  private[this] val write =
    ref1.cas("a", "b") >>> ref2.cas("x", "y")

  @Actor
  def writer(@unused r: LLZ_Result): Unit = {
    write.unsafeRun
  }

  @Actor
  def reader(r: LLZ_Result): Unit = {
    r.r1 = read1.unsafeRun
    r.r2 = read2.unsafeRun
  }

  @Arbiter
  def arbiter(r: LLZ_Result): Unit = {
    val fv1 = impl.read(ref1)
    val fv2 = impl.read(ref2)
    if ((fv1 eq "b") && (fv2 eq "y")) {
      r.r3 = true
    }
  }
}
