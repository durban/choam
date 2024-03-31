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

import cats.syntax.all._

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.paramgen.{ StringGen, BooleanGen }
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }

import munit.FunSuite

import RxnModelTest._

final class RxnModelTest extends FunSuite with BaseLinchkSpec {

  test("Model checking Rxn".tag(SLOW)) {
    val opts = defaultModelCheckingOptions()
    LinChecker.check(classOf[TestState], opts)
  }
}

object RxnModelTest {

  @Param(name = "s", gen = classOf[StringGen])
  @Param(name = "b", gen = classOf[BooleanGen])
  class TestState {

    private[this] val emcas =
      internal.mcas.Mcas.Emcas

    private[this] val r1 =
      Ref.unsafe("a")

    private[this] val r2 =
      Ref.unsafe("b")

    @Operation
    def writeOnly(s: String): (String, String) = {
      (r1.getAndUpdate(s + _), r2.getAndUpdate(s + _)).tupled.unsafeRun(emcas)
    }

    @Operation
    def readWrite(s: String, b: Boolean): (String, String) = {
      val r = if (b) {
        r1.getAndSet.provide(s) * r2.get
      } else {
        r2.getAndSet.provide(s) * r1.get
      }
      r.unsafeRun(emcas)
    }

    @Operation
    def readOnly(): (String, String) = {
      (r2.get, r1.get).tupled.unsafeRun(emcas)
    }
  }
}
