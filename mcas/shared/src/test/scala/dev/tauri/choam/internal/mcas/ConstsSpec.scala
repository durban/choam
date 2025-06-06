/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import munit.CatsEffectSuite

@nowarn("cat=lint-constant") // yes, we have overflows
final class ConstsSpec extends CatsEffectSuite with BaseSpec {

  test("nextPowerOf2") {
    import Consts.nextPowerOf2
    if (!this.isJs()) {
      assertEquals(nextPowerOf2(0), 1) // this is technically not correct, but it's fine for us
    }
    assertEquals(nextPowerOf2(1), 1)
    assertEquals(nextPowerOf2(2), 2)
    assertEquals(nextPowerOf2(3), 4)
    assertEquals(nextPowerOf2(4), 4)
    assertEquals(nextPowerOf2(5), 8)
    assertEquals(nextPowerOf2(6), 8)
    assertEquals(nextPowerOf2(8), 8)
    assertEquals(nextPowerOf2(9), 16)
    assertEquals(nextPowerOf2(15), 16)
    assertEquals(nextPowerOf2(16), 16)
    assertEquals(nextPowerOf2(17), 32)
    assertEquals(nextPowerOf2((1 << 30) - 1), 1 << 30)
    assertEquals(nextPowerOf2(1 << 30), 1 << 30)
    if (!this.isJs()) {
      assertEquals(nextPowerOf2((1 << 30) + 1), 1 << 31)
      assertEquals(nextPowerOf2((1 << 30) + 2), 1 << 31)
      assertEquals(nextPowerOf2((1 << 31) - 1), 1 << 31)
      assertEquals(nextPowerOf2(1 << 31), 1 << 31)
    }
  }
}
