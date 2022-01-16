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

  test("readIntoLog should work (no conflict)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1 ne d0)
    assert(d1 ne null)
    assert(d1.readOnly)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    assert(d2.readOnly)
    // read from r2 (not in the log, but consistent):
    val  Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d3 ne d2)
    assert(d3 ne null)
    assert(d3.readOnly)
    // read from r2 (now in the log):
    val Some((ov2b, d4)) = ctx.readMaybeFromLog(r2, d3) : @unchecked
    assertSameInstance(ov2b, "b")
    assertSameInstance(d4, d3)
    assert(d4.readOnly)
    // writes:
    val d5 = d4.overwrite(d4.getOrElseNull(r1).withNv("aa"))
    assert(!d5.readOnly)
    assert(d5 ne d4)
    val d6 = d5.overwrite(d5.getOrElseNull(r2).withNv("bb"))
    assert(!d6.readOnly)
    val d7 = d6.overwrite(d6.getOrElseNull(r2).withNv("bbb"))
    assert(!d7.readOnly)
    // perform:
    assert(ctx.tryPerform(d7))
    val newVer = d7.validTs + 1
    ctx.setCommitTs(newVer)
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readIfValid(r1, newVer), "aa")
    assertSameInstance(ctx.readIfValid(r2, newVer), "bbb")
  }

  test("readIntoLog should work (version conflict)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change version of r2 (so that it's newer than r1):
    var ok = false
    val t = new Thread(() => {
      val ctx = kcasImpl.currentContext()
      val Some((ov2, dx)) = ctx.readMaybeFromLog(r2, ctx.start()) : @unchecked
      assertSameInstance(ov2, "b")
      val hwd = dx.getOrElseNull(r2)
      assert(hwd ne null)
      val newHwd = hwd.withNv("bb")
      val dx2 = dx.overwrite(newHwd)
      assert(ctx.tryPerform(dx2))
      val newVer = dx2.validTs + 1
      ctx.setCommitTs(newVer)
      assertEquals(ctx.readVersion(r2), newVer)
      assert(newVer > d2.validTs)
      assert(newVer > ctx.readVersion(r1))
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // continue with reading from r2 (version conflict only, will be extended):
    val Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2) : @unchecked
    assertSameInstance(ov2, "bb")
    assert(d3.validTs > d2.validTs)
    assertEquals(d3.getOrElseNull(r1).version, d2.getOrElseNull(r1).version)
    assertEquals(d3.getOrElseNull(r2).version, d3.validTs)
    // read r2 again (it's already in the log):
    val Some((ov3, d4)) = ctx.readMaybeFromLog(r2, d3) : @unchecked
    assertSameInstance(ov3, "bb")
    assertSameInstance(d4, d3)
    assert(d4.readOnly)
    // do some writes:
    val d5 = d4.overwrite(d4.getOrElseNull(r1).withNv("aa"))
    assert(!d5.readOnly)
    val d6 = d5.overwrite(d5.getOrElseNull(r2).withNv("bbb"))
    assert(!d6.readOnly)
    // perform:
    assert(ctx.tryPerform(d6))
    val newVer = d6.validTs + 1
    ctx.setCommitTs(newVer)
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readIfValid(r1, newVer), "aa")
    assertSameInstance(ctx.readIfValid(r2, newVer), "bbb")
  }

  test("readIntoLog should work (real conflict)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change r1 and r2:
    var ok = false
    val t = new Thread(() => {
      val ctx = kcasImpl.currentContext()
      val Some((ov1, dx)) = ctx.readMaybeFromLog(r1, ctx.start()) : @unchecked
      assertSameInstance(ov1, "a")
      val dx2 = dx.overwrite(dx.getOrElseNull(r1).withNv("x"))
      val Some((ov2, dx3)) = ctx.readMaybeFromLog(r2, dx2) : @unchecked
      assertSameInstance(ov2, "b")
      val dx4 = dx3.overwrite(dx3.getOrElseNull(r2).withNv("y"))
      assert(ctx.tryPerform(dx4))
      val newVer = dx4.validTs + 1
      ctx.setCommitTs(newVer)
      assertEquals(ctx.readVersion(r1), newVer)
      assertEquals(ctx.readVersion(r2), newVer)
      assert(newVer > dx4.validTs)
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // continue with reading from r2 (conflict with r1 must be detected):
    val res = ctx.readMaybeFromLog(r2, d2)
    assertSameInstance(res, None) // will need to roll back
  }
}
