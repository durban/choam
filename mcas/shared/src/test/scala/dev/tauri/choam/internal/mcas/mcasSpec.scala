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

import java.util.concurrent.atomic.AtomicInteger

final class McasSpecThreadConfinedMcas
  extends McasSpec
  with SpecThreadConfinedMcas

object McasSpec {

  final case class CASD[A](address: MemoryLocation[A], ov: A, nv: A)
}

abstract class McasSpec extends BaseSpec { this: McasImplSpec =>

  import McasSpec._

  private[this] final def unsafe[A](a: A): MemoryLocation[A] = {
    MemoryLocation.unsafeUnpadded(a, this.rigInstance)
  }

  private final def tryPerformBatch(ops: List[CASD[?]]): Boolean = {
    val ctx = mcasImpl.currentContext()
    val builder = ops.foldLeft(ctx.builder()) { (b, op) =>
      op match {
        case op: CASD[_] =>
          b.casRef(op.address, op.ov, op.nv)
      }
    }
    builder.tryPerformOk()
  }

  test("k-CAS should succeed if old values match, and there is no contention") {
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
    val r3 = unsafe("r3")
    val succ = tryPerformBatch(List(
      CASD(r1, "r1", "x"),
      CASD(r2, "r2", "y"),
      CASD(r3, "r3", "z")
    ))
    assert(succ)
    val ctx = mcasImpl.currentContext()
    assertSameInstance(ctx.readDirect(r1), "x")
    assertSameInstance(ctx.readDirect(r2), "y")
    assertSameInstance(ctx.readDirect(r3), "z")
  }

  test("k-CAS should fail if any of the old values doesn't match") {
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
    val r3 = unsafe("r3")

    def go(): Boolean = {
      tryPerformBatch(List(
        CASD(r1, "r1", "x"),
        CASD(r2, "r2", "y"),
        CASD(r3, "r3", "z")
      ))
    }

    val ctx = mcasImpl.currentContext()

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
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
    val r3 = unsafe("r3")

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

    val ctx = mcasImpl.currentContext()
    assertSameInstance(ctx.readDirect(r1), "x2")
    assertSameInstance(ctx.readDirect(r2), "y2")
    assertSameInstance(ctx.readDirect(r3), "z2")
  }

  test("Snapshotting should work") {
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
    val r3 = unsafe("r3")
    val ctx = mcasImpl.currentContext()
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
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
    val r3 = unsafe("r3")
    val ctx = mcasImpl.currentContext()
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
    val r = unsafe[String]("x")
    assertEquals(mcasImpl.currentContext().readDirect(r), "x")
  }

  test("Platform default must be thread-safe") {
    val inst = Mcas.newDefaultMcas(this.osRngInstance, java.lang.Runtime.getRuntime().availableProcessors())
    try {
      assert(inst.isThreadSafe)
    } finally {
      inst.close()
    }
  }

  test("A successful k-CAS should increase the version of the refs") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
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
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
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
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("r1")
    val r2 = unsafe("r2")
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
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
    val d0 = ctx.start()
    // read from r1 (not in the log):
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    if (this.isEmcas) assert(d1 eq d0) else assert(d1 ne d0)
    assert(d1 ne null)
    assert(d1.readOnly)
    // read from r1 (already in the log):
    val Some((ov1b, d2)) = ctx.readMaybeFromLog(r1, d1, canExtend = true) : @unchecked
    assertSameInstance(ov1b, "a")
    assertSameInstance(d2, d1)
    assert(d2.readOnly)
    // read from r2 (not in the log, but consistent):
    val  Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    if (this.isEmcas) assert(d3 eq d2) else assert(d3 ne d2)
    assert(d3 ne null)
    assert(d3.readOnly)
    // read from r2 (now in the log):
    val Some((ov2b, d4)) = ctx.readMaybeFromLog(r2, d3, canExtend = true) : @unchecked
    assertSameInstance(ov2b, "b")
    assertSameInstance(d4, d3)
    assert(d4.readOnly)
    // writes:
    val d5 = d4.overwrite(d4.getOrElseNull(r1).withNv("aa"))
    assert(!d5.readOnly)
    if (this.isEmcas) assert(d5 eq d4) else assert(d5 ne d4)
    val d6 = d5.overwrite(d5.getOrElseNull(r2).withNv("bb"))
    assert(!d6.readOnly)
    val d7 = d6.overwrite(d6.getOrElseNull(r2).withNv("bbb"))
    assert(!d7.readOnly)
    // snapshot:
    val ds = ctx.snapshot(d7)
    if (isEmcas) assert(ds ne d7) else assert(ds eq d7)
    // perform:
    assertEquals(ctx.tryPerform(d7), McasStatus.Successful)
    val newVer = d7.validTs + 1L
    assertEquals(ctx.readVersion(r1), newVer)
    assertEquals(ctx.readVersion(r2), newVer)
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertSameInstance(ctx.readDirect(r2), "bbb")
  }

  test("tryPerform2 should work (read-only)") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
    val d0 = ctx.start()
    val v1 = ctx.readVersion(r1)
    val v2 = ctx.readVersion(r2)
    val startTs = d0.validTs
    // read both:
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val Some((ov2, d2)) = ctx.readMaybeFromLog(r2, d1, canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    assert(d2.readOnly)
    // commit:
    assert(ctx.tryPerform(d2) == McasStatus.Successful)
    assertEquals(ctx.start().validTs, startTs) // it's read-only, no need to incr version
    assertSameInstance(ctx.readDirect(r1), "a")
    assertEquals(ctx.readVersion(r1), v1)
    assertSameInstance(ctx.readDirect(r2), "b")
    assertEquals(ctx.readVersion(r2), v2)
  }

  test("tryPerform2 should work (read-write)") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
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
    assert(ctx.tryPerform(d4) == McasStatus.Successful)
    val endTs = ctx.start().validTs
    assertEquals(endTs, startTs + Version.Incr)
    assertSameInstance(ctx.readDirect(r1), "b")
    assertEquals(ctx.readVersion(r1), endTs)
    assertSameInstance(ctx.readDirect(r2), "a")
    assertEquals(ctx.readVersion(r2), endTs)
  }

  test("tryPerform2 should work (FailedVal)") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
    val d0 = ctx.start()
    val Some((ov1, d1)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    assert(d1.readOnly)
    val d2 = d1.overwrite(d1.getOrElseNull(r1).withNv("aa"))
    assert(!d2.readOnly)
    val Some((ov2, d3)) = ctx.readMaybeFromLog(r2, d2, canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    assert(!d3.readOnly)
    val d4 = d3.overwrite(d3.getOrElseNull(r2).withNv("bb"))
    assert(!d3.readOnly)
    // simulate concurrent change to r2:
    assert(ctx.tryPerformSingleCas(r2, "b", "x"))
    // the ongoing op should fail:
    val res = ctx.tryPerform(d4)
    assertEquals(res, McasStatus.FailedVal)
    assertSameInstance(ctx.readDirect(r1), "a")
    assertSameInstance(ctx.readDirect(r2), "x")
  }

  test("empty descriptor") {
    val ctx = mcasImpl.currentContext()
    val d0 = ctx.start()
    assert(ctx.tryPerform(d0) == McasStatus.Successful)
  }

  test("singleCasDirect also changes the global commitTs") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val startTs = ctx.start().validTs
    // successful:
    assert(ctx.singleCasDirect(r1, "a", "b"))
    assertEquals(ctx.start().validTs, startTs + 1L)
    assertEquals(ctx.readVersion(r1), startTs + 1L)
    assertSameInstance(ctx.readDirect(r1), "b")
    // failed:
    assert(!ctx.singleCasDirect(r1, "a", "b"))
    assertEquals(ctx.start().validTs, startTs + 1L)
    assertEquals(ctx.readVersion(r1), startTs + 1L)
    assertSameInstance(ctx.readDirect(r1), "b")
    // successful:
    assert(ctx.singleCasDirect(r1, "b", "c"))
    assertEquals(ctx.start().validTs, startTs + 2L)
    assertEquals(ctx.readVersion(r1), startTs + 2L)
    assertSameInstance(ctx.readDirect(r1), "c")
  }

  test("Merging disjoint descriptors should work") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
    val r3 = unsafe("x")
    val d0 = ctx.start()
    val startTs = d0.validTs
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // simulate a concurrent commit which changes global version:
    assert(ctx.tryPerformSingleCas(r3, "x", "y"))
    assertEquals(ctx.start().validTs, startTs + Version.Incr)
    // other side:
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start(), canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    val d2b = d1b.overwrite(d1b.getOrElseNull(r2).withNv("bb"))
    // merge:
    val d3 = ctx.addAll(d2a.toImmutable, d2b.toImmutable, canExtend = true)
    assert(!d3.readOnly)
    assertEquals(d3.validTs, startTs + Version.Incr)
    // commit:
    assert(ctx.tryPerform(d3) == McasStatus.Successful)
    val endTs = ctx.start().validTs
    assertEquals(endTs, startTs + (2 * Version.Incr))
    assertSameInstance(ctx.readDirect(r1), "aa")
    assertEquals(ctx.readVersion(r1), endTs)
    assertSameInstance(ctx.readDirect(r2), "bb")
    assertEquals(ctx.readVersion(r2), endTs)
  }

  test("Merging overlapping descriptors does not work") {
    val ctx = mcasImpl.currentContext()
    val r1 = unsafe("a")
    val r2 = unsafe("b")
    val d0 = ctx.start()
    // one side:
    val Some((ov1, d1a)) = ctx.readMaybeFromLog(r1, d0, canExtend = true) : @unchecked
    assertSameInstance(ov1, "a")
    val d2a = d1a.overwrite(d1a.getOrElseNull(r1).withNv("aa"))
    // other side:
    val Some((ov2, d1b)) = ctx.readMaybeFromLog(r2, ctx.start(), canExtend = true) : @unchecked
    assertSameInstance(ov2, "b")
    // touches the same ref:
    val Some((_, d2b)) = ctx.readMaybeFromLog(r1, d1b, canExtend = true) : @unchecked
    // merge:
    try {
      ctx.addAll(d2a.toImmutable, d2b.toImmutable, canExtend = true)
      fail("expected exception thrown")
    } catch {
      case _: Hamt.IllegalInsertException => // OK
    }
  }

  test("mcas.Descriptor iterator") {
    val ctx = this.mcasImpl.currentContext()
    val r1 = unsafe("foo")
    val r2 = unsafe("foo")
    val d0 = ctx.start()
    assertEquals(d0.size, 0)
    assertEquals(d0.hwdIterator.toList, Nil)
    val d1 = ctx.addCasFromInitial(d0, r1, "foo", "1")
    assertEquals(d1.size, 1)
    val l1 = d1.hwdIterator.toList
    assertEquals(l1.length, 1)
    assertSameInstance(l1(0).address, r1)
    val d2 = ctx.addCasWithVersion(d1, r2, "foo", "2", Version.Start)
    assertEquals(d2.size, 2)
    val l2 = d2.hwdIterator.toList
    assertEquals(l2.length, 2)
    if (MemoryLocation.orderingInstance[String].lt(r1, r2)) {
      assertSameInstance(l2(0).address, r1)
      assertSameInstance(l2(1).address, r2)
    } else {
      assertSameInstance(l2(0).address, r2)
      assertSameInstance(l2(1).address, r1)
    }
    val (expSize, offset) = (2, 0)
    assertEquals(d2.size, expSize)
    val l3 = d2.hwdIterator.toList
    assertEquals(l3.length, expSize)
    if (MemoryLocation.orderingInstance[String].lt(r1, r2)) {
      assertSameInstance(l3(0 + offset).address, r1)
      assertSameInstance(l3(1 + offset).address, r2)
    } else {
      assertSameInstance(l3(0 + offset).address, r2)
      assertSameInstance(l3(1 + offset).address, r1)
    }
  }

  test("equals/hashCode/toString for descriptors") {
    val ctx = this.mcasImpl.currentContext()
    val d1 = ctx.start()
    val r1 = unsafe("foo")
    val r2 = unsafe("foo")
    // hwd:
    val hwd1 = LogEntry[String](r1, "foo", "bar", d1.validTs)
    assertEquals(hwd1, hwd1)
    assertEquals(hwd1.##, hwd1.##)
    val hwd2 = LogEntry[String](r1, "foo", "bar", d1.validTs)
    assertEquals(hwd1, hwd2)
    assertEquals(hwd1.##, hwd2.##)
    assertEquals(hwd1.toString, hwd2.toString)
    val hwd3 = LogEntry[String](r2, "foo", "bar", d1.validTs)
    assertNotEquals(hwd1, hwd3)
    assertNotEquals(hwd1.##, hwd3.##)
    assertNotEquals(hwd1.toString, hwd3.toString)
    // hed:
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
    val d4 = d2.addOrOverwrite(hwd3)
    assertEquals(d4, d3)
    assertEquals(d4.##, d3.##)
    assertEquals(d4.toString, d3.toString)
  }

  test("readOnly for descriptors") {
    val r1 = unsafe("foo")
    val r2 = unsafe("foo")
    val ctx = this.mcasImpl.currentContext()
    val d0 = ctx.start()
    assert(d0.readOnly)
    val d1 = d0.add(LogEntry(r1, "foo", "foo", d0.validTs))
    assert(d1.readOnly)
    if (this.isEmcas) {
      assertSameInstance(d1, d0)
    }
    val d2 = d1.add(LogEntry(r2, "foo", "foo", d0.validTs))
    assert(d2.readOnly)
    if (this.isEmcas) {
      assertSameInstance(d2, d0)
    }
    val d3 = d2.overwrite(LogEntry(r1, "foo", "bar", d0.validTs))
    assert(!d3.readOnly)
    if (this.isEmcas) {
      assertSameInstance(d3, d0)
    }
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
    assert(VersionFunctions.isValid(Version.Start))
    assert(VersionFunctions.isValid(Version.Start + Version.Incr))
    assert(VersionFunctions.isValid(Version.Start + (2 * Version.Incr)))
    assert(VersionFunctions.isValid(Version.Start + (3 * Version.Incr)))
    assert(!VersionFunctions.isValid(Version.None))
    assert(!VersionFunctions.isValid(Version.Active))
    assert(!VersionFunctions.isValid(Version.Successful))
    assert(!VersionFunctions.isValid(Version.FailedVal))
  }

  test("Expected version must also be checked") {
    val ctx = this.mcasImpl.currentContext()
    val ref = unsafe("A")
    assert(ctx.tryPerformSingleCas(ref, "A", "B"))
    val d0 = ctx.start()
    val Some((ov, d1)) = ctx.readMaybeFromLog(ref, d0, canExtend = true) : @unchecked
    assertSameInstance(ov, "B")
    // concurrent changes:
    assert(ctx.tryPerformSingleCas(ref, "B", "C"))
    assert(ctx.tryPerformSingleCas(ref, "C", "B"))
    val ver = ctx.readVersion(ref)
    // now the value is the same, but version isn't:
    assertSameInstance(ctx.readDirect(ref), "B")
    assertNotEquals(ver, d1.getOrElseNull(ref).oldVersion)
    val d2 = d1.overwrite(d1.getOrElseNull(ref).withNv("X"))
    val res = ctx.tryPerform(d2)
    assertEquals(res, McasStatus.FailedVal)
    assertEquals(ctx.readVersion(ref), ver)
  }

  test("subscriber notification should be called on success") {
    val ctx = this.mcasImpl.currentContext()
    val ctr = new AtomicInteger(0)
    val ref = new SimpleMemoryLocation[String]("A")(ctx.refIdGen.nextId()) {
      private[choam] final override def unsafeNotifyListeners(): Unit = {
        ctr.getAndIncrement()
        ()
      }
    }
    val d0 = ctx.start()
    val Some((_, d1)) = ctx.readMaybeFromLog(ref, d0, canExtend = true) : @unchecked
    val d2 = d1.overwrite(d1.getOrElseNull(ref).withNv(("B")))
    assert(ctx.tryPerformOk(d2))
    assertEquals(ctr.get(), 1)
    assertEquals(ctx.readDirect(ref), "B")
  }

  test("subscriber notification should NOT be called on failure") {
    val ctx = this.mcasImpl.currentContext()
    val ctr = new AtomicInteger(0)
    val ref = new SimpleMemoryLocation[String]("A")(ctx.refIdGen.nextId()) {
      private[choam] final override def unsafeNotifyListeners(): Unit = {
        ctr.getAndIncrement()
        ()
      }
    }
    val d0 = ctx.start()
    val Some((_, d1)) = ctx.readMaybeFromLog(ref, d0, canExtend = true) : @unchecked
    val e = d1.getOrElseNull(ref)
    val d2 = d1.overwrite(LogEntry(e.address, "X", "B", e.version))
    assert(!ctx.tryPerformOk(d2))
    assertEquals(ctr.get(), 0)
    assertEquals(ctx.readDirect(ref), "A")
  }

  test("Mcas#isCurrentContext") {
    val impl = this.mcasImpl
    assert(!impl.isCurrentContext(null))
    val ctx = impl.currentContext()
    assert(impl.isCurrentContext(ctx))
  }

  test("Stripes") {
    val ctx0 = this.mcasImpl.currentContext()
    val stripes0 = ctx0.stripes
    assert(stripes0 > 0)
    val stripeId0 = ctx0.stripeId
    assert(stripeId0 >= 0)
    assert(stripeId0 < stripes0)
  }

  test("Temporary buffer") {
    val ctx0 = this.mcasImpl.currentContext()
    val buff = ctx0.buffer16B
    assertEquals(buff.length, 16)
  }
}
