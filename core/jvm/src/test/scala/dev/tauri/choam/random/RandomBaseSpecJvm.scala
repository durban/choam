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
package random

import internal.mcas.Mcas

final class RandomBaseSpecJvm extends RandomBaseSpec {

  private object VarHandleAccess extends RandomBase

  protected[this] override val mcas: Mcas =
    Mcas.newEmcas(this.osRngInstance)

  test("VarHandle endianness") {
    val arr = new Array[Byte](8)
    // get:
    assertEquals(VarHandleAccess.getLongAt0P(arr), 0L)
    arr(0) = 1.toByte
    assertEquals(VarHandleAccess.getLongAt0P(arr), 1L)
    arr(0) = 0.toByte
    arr(7) = 1.toByte
    assertEquals(VarHandleAccess.getLongAt0P(arr), 72057594037927936L)
    arr(7) = 0xff.toByte
    assertEquals(VarHandleAccess.getLongAt0P(arr), -72057594037927936L)
    // put:
    VarHandleAccess.putLongAtIdxP(arr, 0, 0L)
    assertEquals(VarHandleAccess.getLongAt0P(arr), 0L)
    VarHandleAccess.putLongAtIdxP(arr, 0, 42L)
    assertEquals(VarHandleAccess.getLongAt0P(arr), 42L)
  }
}
