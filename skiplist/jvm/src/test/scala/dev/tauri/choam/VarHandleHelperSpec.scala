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

import java.lang.invoke.WrongMethodTypeException

import cats.syntax.all._

final class VarHandleHelperSpec extends munit.FunSuite {

  // TODO: this will fail on JVM < 16
  test("withInvokeExactBehavior") {
    val x = new VarHandleTest
    assert(x.casCorrect(0L, 1L))
    assert(Either.catchOnly[WrongMethodTypeException] { x.casIncorrect(1L, 2L) }.isLeft)
  }
}
