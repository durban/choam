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

import cats.syntax.all._

import random.OsRandom

final class OsRandomSpec extends BaseSpec {

  test("OsRandom#nextBytes") {
    val rng = OsRandom.mkNew()
    assert(Either.catchOnly[IllegalArgumentException] { rng.nextBytes(-1) }.isLeft)
    assertEquals(rng.nextBytes(0).length, 0)
    assertEquals(rng.nextBytes(1).length, 1)
    assertEquals(rng.nextBytes(256).length, 256)
    assertEquals(rng.nextBytes(257).length, 257)
    assertEquals(rng.nextBytes(4096).length, 4096)
    assert(rng.nextBytes(4).exists(_ != 0.toByte))
  }
}
