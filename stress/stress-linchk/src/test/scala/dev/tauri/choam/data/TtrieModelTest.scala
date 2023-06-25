/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package data

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

final class TtrieModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking test of Ttrie".tag(SLOW)) {
    val opts = defaultModelCheckingOptions()
      .checkObstructionFreedom(true)
      .iterations(100)
      .invocationsPerIteration(100)
      .threads(2)
      .actorsBefore(2)
      .actorsPerThread(2)
      .actorsAfter(1)
    printFatalErrors {
      LinChecker.check(classOf[TtrieModelTest.TestState], opts)
    }
  }
}

final object TtrieModelTest {

  @Param(name = "k", gen = classOf[StringGen])
  @Param(name = "v", gen = classOf[StringGen])
  class TestState  {

    private[this] val emcas: mcas.Mcas =
      mcas.Mcas.Emcas

    private[this] val m: Ttrie[String, String] =
      Ttrie[String, String].unsafeRun(emcas)

    @Operation
    def insert(k: String, v: String): Option[String] = {
      m.put.unsafePerform(k -> v, emcas)
    }

    @Operation
    def lookup(k: String): Option[String] = {
      m.get.unsafePerform(k, emcas)
    }
  }
}
