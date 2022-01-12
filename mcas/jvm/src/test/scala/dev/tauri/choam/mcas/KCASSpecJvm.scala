/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

final class KCASSpecJvmEMCAS
  extends KCASSpecJvm
  with SpecEMCAS

final class KCASSpecJvmSpinLockMCAS
  extends KCASSpecJvm
  with SpecSpinLockMCAS

final class KCASSpecJvmThreadConfinedMCAS
  extends KCASSpecJvm
  with SpecThreadConfinedMCAS

abstract class KCASSpecJvm extends KCASSpec { this: KCASImplSpec =>

  test("A successful k-CAS should increase the version of the refs") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.start()
    val d1 = ctx.addCasWithVersion(d0, r1, "r1", "x", v11)
    val d2 = ctx.addCasWithVersion(d1, r2, "r2", "y", v21)
    assert(ctx.tryPerform(d2))
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, v11 + 1L)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, v21 + 1L)
  }

  test("A failed k-CAS should not increase the version of the refs") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.start()
    val d1 = ctx.addCasWithVersion(d0, r1, "r1", "x", v11)
    val d2 = ctx.addCasWithVersion(d1, r2, "-", "y", v21)
    assert(!ctx.tryPerform(d2))
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, v11)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, v21)
  }

  test("Version.Invalid must not be stored".ignore) {
    // TODO: write test
  }
}
