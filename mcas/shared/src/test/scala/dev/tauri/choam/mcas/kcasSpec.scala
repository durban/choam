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

import scala.util.Try

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
    val builder = ops.foldLeft(ctx.builder()) { (b, op) =>
      op match {
        case op: CASD[a] =>
          b.casRef(op.address, op.ov, op.nv)
      }
    }
    builder.tryPerformOk()
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
    assertSameInstance(ctx.readDirect(r1), "x")
    assertSameInstance(ctx.readDirect(r2), "y")
    assertSameInstance(ctx.readDirect(r3), "z")
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

    assert(ctx.tryPerformSingleCas(r1, "r1", "x"))
    assert(!go())
    assertSameInstance(ctx.readDirect(r1), "x")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "r3")

    assert(ctx.tryPerformSingleCas(r1, "x", "r1"))
    assert(ctx.tryPerformSingleCas(r2, "r2", "x"))
    val ok = !go()
    assert(ok)
    assertSameInstance(ctx.readDirect(r1), "r1")
    assertSameInstance(ctx.readDirect(r2), "x")
    assertSameInstance(ctx.readDirect(r3), "r3")

    assert(ctx.tryPerformSingleCas(r2, "x", "r2"))
    assert(ctx.tryPerformSingleCas(r3, "r3", "x"))
    val ok2 = !go()
    assert(ok2)
    assertSameInstance(ctx.readDirect(r1), "r1")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "x")

    assert(ctx.tryPerformSingleCas(r3, "x", "r3"))
    assert(go())
    assertSameInstance(ctx.readDirect(r1), "x")
    assertSameInstance(ctx.readDirect(r2), "y")
    assertSameInstance(ctx.readDirect(r3), "z")
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
    assertSameInstance(ctx.readDirect(r1), "x2")
    assertSameInstance(ctx.readDirect(r2), "y2")
    assertSameInstance(ctx.readDirect(r3), "z2")
  }

  test("Snapshotting should work") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "r1", "r1x")

    val snap = ctx.snapshot(d1)
    val d21 = ctx.addCasFromInitial(d1, r2, "foo", "bar")
    assert(!ctx.tryPerformOk(d21))
    assertSameInstance(ctx.readDirect(r1), "r1")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "r3")
    val d22 = ctx.addCasFromInitial(snap, r3, "r3", "r3x")
    assert(ctx.tryPerformOk(d22))
    assertSameInstance(ctx.readDirect(r1), "r1x")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "r3x")
  }

  test("Snapshotting should work when cancelling") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "r1", "r1x")
    val snap = ctx.snapshot(d1)
    ctx.addCasFromInitial(d1, r2, "foo", "bar") // unused
    assertSameInstance(ctx.readDirect(r1), "r1")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "r3")
    val d22 = ctx.addCasFromInitial(snap, r3, "r3", "r3x")
    assert(ctx.tryPerformOk(d22))
    assertSameInstance(ctx.readDirect(r1), "r1x")
    assertSameInstance(ctx.readDirect(r2), "r2")
    assertSameInstance(ctx.readDirect(r3), "r3x")
  }

  test("read should be able to read from a fresh ref") {
    val r = MemoryLocation.unsafe[String]("x")
    assertEquals(kcasImpl.currentContext().readDirect(r), "x")
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
    assert(ctx.tryPerformOk(d2))
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
    assert(!ctx.tryPerformOk(d2))
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
    val d1 = ctx.addCasFromInitial(d0, r1, "r1", "x")
    val d2 = ctx.addCasWithVersion(d1, r2, "-", "y", v21)
    assert(!ctx.tryPerformOk(d2))
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
    assertEquals(ctx.tryPerform(d7), EmcasStatus.Successful)
    val newVer = d7.newVersion
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertSameInstance(ctx.readDirect(r2), "bbb")
  }

  test("tryPerform2 should work (read-only)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    val v1 = ctx.readVersion(r1)
    val v2 = ctx.readVersion(r2)
    val startTs = d0.validTs
    // read both:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d2.readOnly)
    // commit:
    assert(ctx.tryPerform(d2) == EmcasStatus.Successful)
    assertEquals(ctx.start().validTs, startTs) // it's read-only, no need to incr version
    assertSameInstance(ctx.readDirect(r1), "a")
    assertEquals(ctx.readVersion(r1), v1)
    assertSameInstance(ctx.readDirect(r2), "b")
    assertEquals(ctx.readVersion(r2), v2)
  }

  test("tryPerform2 should work (read-write)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    val startTs = d0.validTs
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
    assert(ctx.tryPerform(d4) == EmcasStatus.Successful)
    val endTs = ctx.start().validTs
    assertEquals(endTs, startTs + Version.Incr)
    assertSameInstance(ctx.readDirect(r1), "b")
    assertEquals(ctx.readVersion(r1), endTs)
    assertSameInstance(ctx.readDirect(r2), "a")
    assertEquals(ctx.readVersion(r2), endTs)
  }

  test("tryPerform2 should work (FailedVal)") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("aa"))
    assert(!d2.readOnly)
    val Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2) : @unchecked
    assertSameInstance(ov2, "b")
    assert(!d3.readOnly)
    val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("bb"))
    assert(!d3.readOnly)
    // simulate concurrent change to r2:
    assert(ctx.tryPerformSingleCas(r2, "b", "x"))
    // the ongoing op should fail:
    val res = ctx.tryPerform(d4)
    assertEquals(res, EmcasStatus.FailedVal)
    assertSameInstance(ctx.readDirect(r1), "a")
    assertSameInstance(ctx.readDirect(r2), "x")
  }

  test("singleCasDirect should work without changing the global commitTs") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val startTs = ctx.start().validTs
    // successful:
    assert(ctx.singleCasDirect(r1, "a", "b"))
    assertEquals(ctx.start().validTs, startTs)
    assertEquals(ctx.readVersion(r1), startTs)
    assertSameInstance(ctx.readDirect(r1), "b")
    // failed:
    assert(!ctx.singleCasDirect(r1, "a", "b"))
    assertEquals(ctx.start().validTs, startTs)
    assertEquals(ctx.readVersion(r1), startTs)
    assertSameInstance(ctx.readDirect(r1), "b")
  }

  test("Merging disjoint descriptors should work") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val r3 = MemoryLocation.unsafe("x")
    val d0 = ctx.start()
    val startTs = d0.validTs
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // simulate a concurrent commit which changes global version:
    assert(ctx.tryPerformSingleCas(r3, "x", "y"))
    assertEquals(ctx.start().validTs, startTs + Version.Incr)
    // other side:
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start()) : @unchecked
    assertSameInstance(ov2, "b")
    val d2b = d1b.overwrite(d1b.getOrElseNull(r2).withNv("bb"))
    // merge:
    val d3 = ctx.addAll(d2a, d2b)
    assert(!d3.readOnly)
    assertEquals(d3.validTs, startTs + Version.Incr)
    // commit:
    assert(ctx.tryPerform(d3) == EmcasStatus.Successful)
    val endTs = ctx.start().validTs
    assertEquals(endTs, startTs + (2 * Version.Incr))
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertEquals(ctx.readVersion(r1), endTs)
    assertSameInstance(ctx.readDirect(r2), "bb")
    assertEquals(ctx.readVersion(r2), endTs)
  }

  test("Merging overlapping descriptors does not work") {
    val ctx = kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("a")
    val r2 = MemoryLocation.unsafe("b")
    val d0 = ctx.start()
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // other side:
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start()) : @unchecked
    assertSameInstance(ov2, "b")
    // touches the same ref:
    val Some((_, d2b)) = ctx.readMaybeFromLog(r1, d1b) : @unchecked
    // merge:
    try {
      ctx.addAll(d2a, d2b)
      fail("expected exception thrown")
    } catch {
      case _: IllegalArgumentException => // OK
    }
  }

  test("equals/hashCode/toString for descriptors") {
    val ctx = this.kcasImpl.currentContext()
    val r1 = MemoryLocation.unsafe("foo")
    val r2 = MemoryLocation.unsafe("foo")
    // hwd:
    val hwd1 = HalfWordDescriptor[String](r1, "foo", "bar", 42L)
    assertEquals(hwd1, hwd1)
    assertEquals(hwd1.##, hwd1.##)
    val hwd2 = HalfWordDescriptor[String](r1, "foo", "bar", 42L)
    assertEquals(hwd1, hwd2)
    assertEquals(hwd1.##, hwd2.##)
    assertEquals(hwd1.toString, hwd2.toString)
    val hwd3 = HalfWordDescriptor[String](r2, "foo", "bar", 42L)
    assertNotEquals(hwd1, hwd3)
    assertNotEquals(hwd1.##, hwd3.##)
    assertNotEquals(hwd1.toString, hwd3.toString)
    // hed:
    val d1 = ctx.start()
    assertEquals(d1, d1)
    assertEquals(d1.##, d1.##)
    val d2 = ctx.start()
    assertEquals(d1, d2)
    assertEquals(d1.##, d2.##)
    assertEquals(d1.toString, d2.toString)
    val d3 = d2.add(hwd3)
    assertNotEquals(d1, d3)
    assertNotEquals(d1.##, d3.##)
    assertNotEquals(d1.toString, d3.toString)
    val d4 = d2.add(hwd3)
    assertEquals(d4, d3)
    assertEquals(d4.##, d3.##)
    assertEquals(d4.toString, d3.toString)
    val d5 = d4.withNoNewVersion
    assertNotEquals(d5, d4)
    assertNotEquals(d5.##, d4.##)
    assertNotEquals(d5.toString, d4.toString)
  }

  test("Version numbers must be unique") {
    assertEquals(Version.None, Long.MaxValue)
    val set = Set[Long](
      Version.Start,
      Version.None,
      Version.Active,
      Version.Successful,
      Version.FailedVal,
    )
    assertEquals(clue(set).size, 5)
    assert(Version.isValid(Version.Start))
    assert(Version.isValid(Version.Start + Version.Incr))
    assert(Version.isValid(Version.Start + (2 * Version.Incr)))
    assert(Version.isValid(Version.Start + (3 * Version.Incr)))
    assert(!Version.isValid(Version.None))
    assert(!Version.isValid(Version.Active))
    assert(!Version.isValid(Version.Successful))
    assert(!Version.isValid(Version.FailedVal))
  }

  test("CommitTs ref must be the last (common)") {
    val cts1 = MemoryLocation.unsafeCommitTsRef(padded = true)
    val cts2 = MemoryLocation.unsafeCommitTsRef(padded = false)
    val random = MemoryLocation.unsafe[Long](42L)
    assert(MemoryLocation.orderingInstance.compare(cts1, random) > 0)
    assert(MemoryLocation.orderingInstance.compare(cts2, random) > 0)
    assert(Try(MemoryLocation.orderingInstance.compare(cts1, cts2)).isFailure)
  }
}
