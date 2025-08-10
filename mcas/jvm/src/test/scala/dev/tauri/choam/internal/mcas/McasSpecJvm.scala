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

import scala.collection.concurrent.TrieMap

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
    val r1 = MemoryLocation.unsafeUnpadded("a", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded("b", this.rigInstance)
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    if (this.isEmcas) assert(d1 eq d0) else assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1, canExtend = true) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change version of r2 (so that it's newer than r1):
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val Some((ov2, dx)) = ctx.readMaybeFromLog(r2, ctx.start(), canExtend = true) : @unchecked
      assertSameInstance(ov2, "b")
      val hwd = dx.getOrElseNull(r2)
      assert(hwd ne null)
      val newHwd = hwd.withNv("bb")
      val dx2 = dx.overwrite(newHwd)
      assertEquals(ctx.tryPerform(dx2), McasStatus.Successful)
      val newVer = dx2.validTs + 1L
      assertEquals(ctx.readVersion(r2), newVer)
      assert(newVer > d2.validTs)
      assert(newVer > ctx.readVersion(r1))
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // create an immutable copy (to test an alternative path where canExtend = false):
    val d2Imm = d2.toImmutable
    // continue with reading from r2 (version conflict only, will be extended):
    val d2vt = d2.validTs
    val Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
    val d3vt = d3.validTs
    assertSameInstance(ov2, "bb")
    assert(d3vt > d2vt)
    assertEquals(d3.getOrElseNull(r1).version, d2.getOrElseNull(r1).version)
    assertEquals(d3.getOrElseNull(r2).version, d3.validTs)
    // OR, alternatively, will not be extended if it's not allowed:
    assertEquals(d2Imm.validTs, d2vt)
    val res = ctx.readMaybeFromLog(r2, d2Imm, canExtend = false)
    assertEquals(res, None)
    // END of alternative path
    // read r2 again (it's already in the log):
    val Some((ov3, d4)) = ctx.readMaybeFromLog(r2, d3, canExtend = true) : @unchecked
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
    val newVer = d6.validTs + 1L
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertSameInstance(ctx.readDirect(r2), "bbb")
  }

  test("readIntoLog should work (real conflict)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafeUnpadded("a", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded("b", this.rigInstance)
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    if (this.isEmcas) assert(d1 eq d0) else assert(d1 ne d0)
    assert(d1 ne null)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1, canExtend = true) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    // concurrently change r1 and r2:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val Some((ov1, dx)) = ctx.readMaybeFromLog(r1, ctx.start(), canExtend = true) : @unchecked
      assertSameInstance(ov1, "a")
      val dx2 = dx.overwrite(dx.getOrElseNull(r1).withNv("x"))
      val Some((ov2, dx3)) = ctx.readMaybeFromLog(r2, dx2, canExtend = true) : @unchecked
      assertSameInstance(ov2, "b")
      val dx4 = dx3.overwrite(dx3.getOrElseNull(r2).withNv("y"))
      assertEquals(ctx.tryPerform(dx4), McasStatus.Successful)
      val newVer = dx4.validTs + 1L
      assertEquals(ctx.readVersion(r1), newVer)
      assertEquals(ctx.readVersion(r2), newVer)
      assert(newVer > dx4.validTs)
      ok = true
    })
    t.start()
    t.join()
    assert(ok)
    // continue with reading from r2 (conflict with r1 must be detected):
    val res = ctx.readMaybeFromLog(r2, d2, canExtend = true)
    assertSameInstance(res, None) // will need to roll back
    val hwd = ctx.readIntoHwd(r2)
    assert(!d2.isValidHwd(hwd))
  }

  test("tryPerform2 should work (read-only, concurrent commit)") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafeUnpadded("a", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded("b", this.rigInstance)
    val d0 = ctx.start()
    val startTs = d0.validTs
    // read both:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1, canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d2.readOnly)
    // concurrent commit:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val d0 = ctx.start()
      val Some((_, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
      val Some((_, d2)) = ctx.readMaybeFromLog(r2, d1, canExtend = true) : @unchecked
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
    val r1 = MemoryLocation.unsafeUnpadded("a", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded("b", this.rigInstance)
    val r3 = MemoryLocation.unsafeUnpadded("c", this.rigInstance)
    val d0 = ctx.start()
    val startTs = d0.validTs
    // swap contents:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1, canExtend = true) : @unchecked
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
      val Some((_, d1)) = ctx.readMaybeFromLog(r3, d0, canExtend = true) : @unchecked
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
    assertEquals(res, McasStatus.Successful)
    assertEquals(endTs, startTs + (2 * Version.Incr))
    assertSameInstance(ctx.readDirect(r1), "b")
    assertEquals(ctx.readVersion(r1), endTs)
    assertSameInstance(ctx.readDirect(r2), "a")
    assertEquals(ctx.readVersion(r2), endTs)
    assertEquals(ctx.readVersion(r3), endTs - Version.Incr)
    assertSameInstance(ctx.readDirect(r3), "cc")
  }

  test("Merging must detect if the logs are inconsistent") {
    val ctx = mcasImpl.currentContext()
    val r1 = MemoryLocation.unsafeUnpadded("a", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded("b", this.rigInstance)
    val d0 = ctx.start()
    val startTs = d0.validTs
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // concurrent:
    var ok = false
    val t = new Thread(() => {
      val ctx = mcasImpl.currentContext()
      val d0 = ctx.start()
      val Some((_, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
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
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start(), canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    val d2b = d1b.overwrite(d1b.getOrElseNull(r2).withNv("bb"))
    // try to merge; the logs are disjoint, but inconsistent:
    // `d2a` cannot be extended; will need to rollback/retry
    assert(ctx.addAll(d2a.toImmutable, d2b.toImmutable, canExtend = true) eq null)
  }

  test("Stripes multithreaded") {
    val N = 64
    val ctx0 = this.mcasImpl.currentContext()
    val stripes = new TrieMap[Int, Unit]
    val stripeIds = new TrieMap[Int, Unit]
    val threads = Vector.fill(N) {
      new Thread(() => {
        val ctx1 = this.mcasImpl.currentContext()
        stripes.put(ctx1.stripes, ())
        stripeIds.put(ctx1.stripeId, ())
        ()
      })
    }
    threads.foreach(_.start())
    stripes.put(ctx0.stripes, ())
    stripeIds.put(ctx0.stripeId, ())
    threads.foreach(_.join())
    assertEquals(stripes.size, 1)
    val stripeCount = stripes.keysIterator.next()
    assert(stripeCount > 0)
    assertEquals(stripeIds.size, java.lang.Runtime.getRuntime().availableProcessors())
    stripeIds.foreachEntry { (k, _) =>
      assert(k < stripeCount)
      assert(k >= 0)
    }
  }
}
