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

import org.jetbrains.kotlinx.lincheck.{ LinChecker, LincheckAssertionError }
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.annotations.{ Operation, Param }
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

import munit.FunSuite

@Param(name = "v", gen = classOf[IntGen], conf = "0:127")
class ManagedTestState {

  @volatile
  private[this] var count: Int =
    0

  @Operation
  def incr(v: Int): Int = {
    val curr = this.count
    this.count = curr + v
    curr
  }

  @Operation
  def decr(v: Int): Int = {
    val curr = this.count
    this.count = curr - v
    curr
  }
}

final class ManagedTestExample extends FunSuite with BaseLinchkSpec {

  test("Dummy counter test") {
    val opts = new ModelCheckingOptions()
    try {
      LinChecker.check(classOf[ManagedTestState], opts)
      fail("expected a lincheck failure")
    } catch {
      case _: LincheckAssertionError =>
        () // ok, expected failure
    }
  }
}
