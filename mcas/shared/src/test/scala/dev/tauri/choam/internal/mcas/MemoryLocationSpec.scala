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

final class MemoryLocationSpec extends BaseSpec with SpecDefaultMcas {

  def mkTestRefs(): List[MemoryLocation[String]] = {
    List(
      MemoryLocation.unsafeUnpadded("foo", this.rigInstance),
      MemoryLocation.unsafePadded("foo", this.rigInstance),
    )
  }

  test("MemoryLocation#unsafeCmpxchgVersionV") {
    for (ref <- mkTestRefs()) {
      val wit = ref.unsafeCmpxchgVersionV(Version.Start, 42L)
      assertEquals(wit, Version.Start)
      assertEquals(ref.unsafeGetVersionV(), 42L)
      val wit2 = ref.unsafeCmpxchgVersionV(0L, 99L)
      assertEquals(wit2, 42L)
      assertEquals(ref.unsafeGetVersionV(), 42L)
    }
  }

  test("MemoryLocation hashCode and equals") {
    val l1 = MemoryLocation.unsafeUnpadded("foo", this.rigInstance)
    assertEquals(l1.##, l1.id.toInt)
    val l2 = MemoryLocation.unsafePadded("foo", this.rigInstance)
    assertEquals(l2.##, l2.id.toInt)
  }
}
