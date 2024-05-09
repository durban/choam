/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.syntax.all._

final class McasSpecJvmEmcas
  extends McasSpecJvm
  with SpecEmcas

final class McasSpecJvmSpinLockMcas
  extends McasSpecJvm
  with SpecSpinLockMcas

final class McasSpecJvmThreadConfinedMcas
  extends McasSpecJvm
  with SpecThreadConfinedMcas

abstract class McasSpecJvm extends McasSpec { this: McasImplSpec =>

  test("readIntoLog should work (version conflict)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    if (this.isEmcas) assert(d1 eq d0) else assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change version of r2 (so that it's newer than r1):
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val Some((ov2, dx)) = ctx.readMaybeFromLog(r2, ctx.start()) : @unchecked
      assertSameInstance(ov2, "b")
      val hwd = dx.getOrElseNull(r2)
      assert(hwd ne null)
      val newHwd = hwd.withNv("bb")
      val dx2 = dx.overwrite(newHwd)
      assertEquals(ctx.tryPerform(dx2), McasStatus.Successful)
      val newVer = dx2.newVersion
      assertEquals(ctx.readVersion(r2), newVer)
      assert(newVer > d2.validTs)
      assert(newVer > ctx.readVersion(r1))
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // continue with reading from r2 (version conflict only, will be extended):
    val d2vt = d2.validTs
    val Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2) : @unchecked
    val d3vt = d3.validTs
    assertSameInstance(ov2, "bb")
    assert(d3vt > d2vt)
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
    assertEquals(ctx.tryPerform(d6), McasStatus.Successful)
    val newVer = d6.newVersion
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertSameInstance(ctx.readDirect(r2), "bbb")
  }

  test("readIntoLog should work (real conflict)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    if (this.isEmcas) assert(d1 eq d0) else assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change r1 and r2:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val Some((ov1, dx)) = ctx.readMaybeFromLog(r1, ctx.start()) : @unchecked
      assertSameInstance(ov1, "a")
      val dx2 = dx.overwrite(dx.getOrElseNull(r1).withNv("x"))
      val Some((ov2, dx3)) = ctx.readMaybeFromLog(r2, dx2) : @unchecked
      assertSameInstance(ov2, "b")
      val dx4 = dx3.overwrite(dx3.getOrElseNull(r2).withNv("y"))
      assertEquals(ctx.tryPerform(dx4), McasStatus.Successful)
      val newVer = dx4.newVersion
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
    val hwd = ctx.readIntoHwd(r2)
    assert(!d2.isValidHwd(hwd))
  }

  test("tryPerform2 should work (read-only, concurrent commit)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    val startTs = d0.validTs
    // read both:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d2.readOnly)
    // concurrent commit:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val d0 = ctx.start()
      val Some((_, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
      val Some((_, d2)) = ctx.readMaybeFromLog(r2, d1) : @unchecked
      val d3 = d2.overwrite(d2.getOrElseNull(r1).withNv("aa"))
      val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("bb"))
      assertEquals(ctx.tryPerform(d4), McasStatus.Successful)
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // commit:
    assertEquals(ctx.tryPerform(d2), McasStatus.Successful) // read-only should still succeed
    assertEquals(ctx.start().validTs, startTs + Version.Incr) //concurrent commit increased
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertEquals(ctx.readVersion(r1), startTs + Version.Incr)
    assertSameInstance(ctx.readDirect(r2), "bb")
    assertEquals(ctx.readVersion(r2), startTs + Version.Incr)
  }

  test("tryPerform2 should work (read-write, concurrent commit)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val r3 = MemoryLocation.unsafe("c")
    val d0 = ctx.start()
    val startTs = d0.validTs
    val v1 = ctx.readVersion(r1)
    val v2 = ctx.readVersion(r2)
    // swap contents:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d2.readOnly)
    val d3 = d2.overwrite(d2.getOrElseNull(r1).withNv(ov2))
    assert(!d3.readOnly)
    val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv(ov1))
    assert(!d4.readOnly)
    // concurrent commit changes an unrelated ref:
    var ok = false
    var newVer: Long = Version.None
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val d0 = ctx.start()
      val Some((_, d1)) = ctx.readMaybeFromLog(r3, d0) : @unchecked
      val d2 = d1.overwrite(d1.getOrElseNull(r3).withNv("cc"))
      assertEquals(ctx.tryPerform(d2), McasStatus.Successful)
      newVer = ctx.start().validTs
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // try to finish the swap:
    val res = ctx.tryPerform(d4)
    val endTs = ctx.start().validTs
    // EMCAS should be able to handle this (disjoint op), but
    // others should fail due to the version-CAS failing:
    if (this.isEmcas) {
      assertEquals(res, McasStatus.Successful)
      assertEquals(endTs, startTs + (2 * Version.Incr))
      assertSameInstance(ctx.readDirect(r1), "b")
      assertEquals(ctx.readVersion(r1), endTs)
      assertSameInstance(ctx.readDirect(r2), "a")
      assertEquals(ctx.readVersion(r2), endTs)
      assertEquals(ctx.readVersion(r3), endTs - Version.Incr)
    } else {
      assertEquals(res, newVer)
      assert(Version.isValid(res))
      assertEquals(endTs, startTs + Version.Incr)
      assertSameInstance(ctx.readDirect(r1), "a")
      assertEquals(ctx.readVersion(r1), v1)
      assertSameInstance(ctx.readDirect(r2), "b")
      assertEquals(ctx.readVersion(r2), v2)
      assertEquals(ctx.readVersion(r3), endTs)
    }
    assertSameInstance(ctx.readDirect(r3), "cc")
  }

  test("Merging must detect if the logs are inconsistent") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    val startTs = d0.validTs
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // concurrent:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val d0 = ctx.start()
      val Some((_, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
      val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("x"))
      assertEquals(ctx.tryPerform(d2), McasStatus.Successful)
      assertSameInstance(ctx.readDirect(r1), "x")
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    assert(ctx.readVersion(r1) > startTs)
    // other side:
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start()) : @unchecked
    assertSameInstance(ov2, "b")
    val d2b = d1b.overwrite(d1b.getOrElseNull(r2).withNv("bb"))
    // try to merge; the logs are disjoint, but inconsistent:
    // `d2a` cannot be extended; will need to rollback/retry
    assert(ctx.addAll(d2a.toImmutable, d2b.toImmutable) eq null)
  }

  test("CommitTs ref must be the first (JVM)") {
    assume(!this.isEmcas)
    val r1 = MemoryLocation.unsafe[String]("foo")
    val r2 = MemoryLocation.unsafe[String]("bar")
    val ctx = this.mcasImpl.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "foo", "bar")
    val d2 = ctx.addCasFromInitial(d1, r2, "bar", "foo")
    val d3 = ctx.addVersionCas(d2)
    val lb = List.newBuilder[MemoryLocation[_]]
    val it = d3.hwdIterator(ctx)
    while (it.hasNext) {
      lb += it.next().address
    }
    val lst: List[MemoryLocation[_]] = lb.result()
    assertEquals(lst.length, 3)
    assert((lst(1) eq r1) || (lst(1) eq r2))
    assert((lst(2) eq r1) || (lst(2) eq r2))
    assert(lst(0) ne r1)
    assert(lst(0) ne r2)
  }

  test("Descriptor mustn't allow EMCAS to use `hwdIterator`") {
    // because it's slow
    val r1 = MemoryLocation.unsafe[String]("foo")
    val ctx = emcas.Emcas.inst.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "foo", "bar")
    assert(Either.catchOnly[IllegalArgumentException] { d1.hwdIterator(ctx) }.isLeft)
  }
}
