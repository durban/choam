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

import cats.syntax.all._

final class AssertSpec extends BaseSpec {

  test("_assert should be enabled during tests (for now)".fail) {
    _assert(false)
  }

  private final def useJsAssert(): Unit = {
    jsAssert(false)
  }

  test("jsAssert should work on JS, but should be a NOP otherwise") {
    jsAssert(true) // always NOP
    val ei = Either.catchOnly[AssertionError] { useJsAssert() }
    if (this.isJs()) {
      assert(ei.isLeft)
    } else {
      assert(ei.isRight)
    }
  }

  private final def useJsCheckIdx1(): Unit = {
    jsCheckIdx(-1, 3)
  }

  private final def useJsCheckIdx2(): Unit = {
    jsCheckIdx(3, 3)
  }

  test("jsCheckIdx should work on JS, but should be a NOP otherwise") {
    jsCheckIdx(2, 3) // always NOP
    val eis = List(
      Either.catchOnly[IndexOutOfBoundsException] { useJsCheckIdx1() },
      Either.catchOnly[IndexOutOfBoundsException] { useJsCheckIdx2() },
    )
    for (ei <- eis) {
      if (this.isJs()) {
        assert(ei.isLeft)
      } else {
        assert(ei.isRight)
      }
    }
  }
}
