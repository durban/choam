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
package internal
package mcas

import java.io.IOException

import cats.syntax.either._

final class OsRngInternalSpec extends BaseSpec {

  test("UnixRng should be closeable") {
    this.assumeNotWin()
    val rng = OsRng.mkNew()
    assertEquals(rng.getClass().getSimpleName(), "UnixRng")
    assertEquals(rng.nextBytes(3).length, 3)
    rng.close()
    assert(Either.catchOnly[IOException] { rng.nextBytes(3) }.isLeft)
  }
}
