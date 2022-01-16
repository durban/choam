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

final class KCASSpecThreadConfinedMCAS
  extends KCASSpec
  with SpecThreadConfinedMCAS

object KCASSpec {

  final case class CASD[A](address: MemoryLocation[A], ov: A, nv: A)
}

abstract class KCASSpec extends BaseSpecA { this: KCASImplSpec =>

  import KCASSpec._

  private final def tryPerformBatch(ops: List[CASD[_]]): Boolean = {
    val ctx = kcasImpl.currentContext()
    val desc = ops.foldLeft(ctx.start()) { (d, op) =>
      op match {
        case op: CASD[a] =>
          ctx.addCas(d, op.address, op.ov, op.nv)
      }
    }
    ctx.tryPerform(desc)
  }

  test("k-CAS should succeed if old values match, and there is no contention") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val succ = tryPerformBatch(List(
      CASD(r1, "r1", "x"),
      CASD(r2, "r2", "y"),
      CASD(r3, "r3", "z")
    ))
    assert(succ)
    val ctx = kcasImpl.currentContext()
    assertSameInstance(ctx.read(r1), "x")
    assertSameInstance(ctx.read(r2), "y")
    assertSameInstance(ctx.read(r3), "z")
  }

  test("k-CAS should fail if any of the old values doesn't match") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")

    def go(): Boolean = {
      tryPerformBatch(List(
        CASD(r1, "r1", "x"),
        CASD(r2, "r2", "y"),
        CASD(r3, "r3", "z")
      ))
    }

    val ctx = kcasImpl.currentContext()

    assert(ctx.doSingleCas(r1, "r1", "x"))
    assert(!go())
    assertSameInstance(ctx.read(r1), "x")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "r3")

    assert(ctx.doSingleCas(r1, "x", "r1"))
    assert(ctx.doSingleCas(r2, "r2", "x"))
    val ok = !go()
    assert(ok)
    assertSameInstance(ctx.read(r1), "r1")
    assertSameInstance(ctx.read(r2), "x")
    assertSameInstance(ctx.read(r3), "r3")

    assert(ctx.doSingleCas(r2, "x", "r2"))
    assert(ctx.doSingleCas(r3, "r3", "x"))
    val ok2 = !go()
    assert(ok2)
    assertSameInstance(ctx.read(r1), "r1")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "x")

    assert(ctx.doSingleCas(r3, "x", "r3"))
    assert(go())
    assertSameInstance(ctx.read(r1), "x")
    assertSameInstance(ctx.read(r2), "y")
    assertSameInstance(ctx.read(r3), "z")
  }

  test("k-CAS should not accept more than one CAS for the same ref") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val exc = intercept[Exception] {
      tryPerformBatch(List(
        CASD(r1, "r1", "x"),
        CASD(r2, "r2", "y"),
        CASD(r1, "r1", "x") // this is a duplicate
      ))
    }
    assert(clue(exc.getMessage).contains("Impossible k-CAS"))
    assert(clue(exc).isInstanceOf[ImpossibleOperation])
    val ctx = kcasImpl.currentContext()
    assertSameInstance(ctx.read(r1), "r1")
    assertSameInstance(ctx.read(r2), "r2")
  }

  test("k-CAS should be able to succeed after one successful operation") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")

    assert(tryPerformBatch(List(
      CASD(r1, "r1", "x"),
      CASD(r2, "r2", "y"),
      CASD(r3, "r3", "z")
    )))

    assert(tryPerformBatch(List(
      CASD(r1, "x", "x2"),
      CASD(r2, "y", "y2"),
      CASD(r3, "z", "z2")
    )))

    assert(!tryPerformBatch(List(
      CASD(r1, "x2", "x3"),
      CASD(r2, "yyy", "y3"), // this will fail
      CASD(r3, "z2", "z3")
    )))

    val ctx = kcasImpl.currentContext()
    assertSameInstance(ctx.read(r1), "x2")
    assertSameInstance(ctx.read(r2), "y2")
    assertSameInstance(ctx.read(r3), "z2")
  }

  test("Snapshotting should work") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCas(d0, r1, "r1", "r1x")

    val snap = ctx.snapshot(d1)
    val d21 = ctx.addCas(d1, r2, "foo", "bar")
    assert(!ctx.tryPerform(d21))
    assertSameInstance(ctx.read(r1), "r1")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "r3")
    val d22 = ctx.addCas(snap, r3, "r3", "r3x")
    assert(ctx.tryPerform(d22))
    assertSameInstance(ctx.read(r1), "r1x")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "r3x")
  }

  test("Snapshotting should work when cancelling") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCas(d0, r1, "r1", "r1x")
    val snap = ctx.snapshot(d1)
    ctx.addCas(d1, r2, "foo", "bar") // unused
    assertSameInstance(ctx.read(r1), "r1")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "r3")
    val d22 = ctx.addCas(snap, r3, "r3", "r3x")
    assert(ctx.tryPerform(d22))
    assertSameInstance(ctx.read(r1), "r1x")
    assertSameInstance(ctx.read(r2), "r2")
    assertSameInstance(ctx.read(r3), "r3x")
  }

  test("read should be able to read from a fresh ref") {
    val r = MemoryLocation.unsafe[String]("x")
    assertEquals(kcasImpl.currentContext().read(r), "x")
  }

  test("Platform default must be thread-safe") {
    assert(MCAS.DefaultMCAS.isThreadSafe)
  }

  test("A successful k-CAS should increase the version of the refs") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.start()
    assert(d0.validTs >= v11)
    assert(d0.validTs >= v21)
    val d1 = ctx.addCasWithVersion(d0, r1, "r1", "x", v11)
    val d2 = ctx.addCasWithVersion(d1, r2, "r2", "y", v21)
    assert(ctx.tryPerform(d2))
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, d0.validTs + 1L)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, d0.validTs + 1L)
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

  test("Version.None must not be stored") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.start()
    val d1 = ctx.addCas(d0, r1, "r1", "x")
    val d2 = ctx.addCasWithVersion(d1, r2, "-", "y", v21)
    assert(!ctx.tryPerform(d2))
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, v11)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, v21)
  }

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
}
