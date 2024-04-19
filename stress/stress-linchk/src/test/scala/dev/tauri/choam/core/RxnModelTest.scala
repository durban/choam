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

    private[this] val emcas =
      internal.mcas.Mcas.Emcas

    private[this] val r1 =
      Ref.unsafeUnpadded("a")

    private[this] val r2 =
      Ref.unsafeUnpadded("b")

    private[this] val r3 =
      Ref.unsafeUnpadded("c")

    private[this] def select2(i: Int): (Ref[String], Ref[String]) = {
      java.lang.Math.abs(i % 6) match {
        case 0 => (r2, r3)
        case 1 => (r1, r3)
        case 2 => (r1, r2)
        case 3 => (r3, r2)
        case 4 => (r3, r1)
        case 5 => (r2, r1)
      }
    }

    @Operation
    def writeOnly(s: String, t: String, i: Int): (String, String) = {
      val (ref1, ref2) = this.select2(i)
      (ref1.getAndUpdate(s + _), ref2.getAndUpdate(t + _)).tupled.unsafeRun(emcas)
    }

    @Operation
    def readWrite(s: String, i: Int): (String, String) = {
      val (ref1, ref2) = this.select2(i)
      (ref1.getAndSet.provide(s) * ref2.get).unsafeRun(emcas)
    }

    @Operation
    def readOnly(b: Boolean): (String, String, String) = {
      val tup = if (b) {
        (r1.get, r2.get, r3.get)
      } else {
        (r2.get, r1.get, r3.get)
      }
      tup.tupled.unsafeRun(emcas)
    }
  }
}
