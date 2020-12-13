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
package macros

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZZ_Result

import kcas.KCAS

@KCASParams("Dummy test", true)
@Outcomes(Array(
  new Outcome(id = Array("true, true, true"), expect = ACCEPTABLE, desc = "OK"),
  new Outcome(id = Array("false, true, true"), expect = ACCEPTABLE, desc = "OK too")
))
abstract class DummyTest(impl: KCAS) {

  final def kcasImplPublic: KCAS = kcasImpl

  def foo(@deprecated("unused", "") i: Int): Unit = {
    ()
  }

  @Actor
  def actor1(r: ZZZ_Result): Unit = {
    r.r1 = impl.## > 0
  }

  @Actor
  def actor2(r: ZZZ_Result): Unit = {
    r.r2 = true
  }

  @Arbiter
  def arbiter(r: ZZZ_Result): Unit = {
    r.r3 = r.r1 || r.r2
  }
}
