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
package core

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.{ StringGen, BooleanGen, IntGen }
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

import RxnModelTest._

final class RxnModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking Rxn".tag(SLOW)) {
    val opts = longModelCheckingOptions()
    LinChecker.check(classOf[TestState], opts)
  }
}

object RxnModelTest {

  @Param(name = "s", gen = classOf[StringGen])
  @Param(name = "t", gen = classOf[StringGen])
  @Param(name = "b", gen = classOf[BooleanGen])
  @Param(name = "i", gen = classOf[IntGen])
  class TestState {

    private[this] val inst =
      new dev.tauri.choam.lcdebug.ChoamInstance

    @Operation
    def writeOnly(s: String, t: String, i: Int): (String, String) = {
      inst.writeOnly(s, t, i)
    }

    @Operation
    def readWrite(s: String, i: Int): (String, String) = {
      inst.readWrite(s, i)
    }

    @Operation
    def readOnly(b: Boolean): (String, String, String) = {
      inst.readOnly(b)
    }
  }
}
