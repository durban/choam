/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

final class KCASSpecNaiveKCAS
  extends KCASSpec
  with SpecNaiveKCAS

final class KCASSpecEMCAS
  extends KCASSpec
  with SpecEMCAS

object KCASSpec {

  final case class CASD[A](address: MemoryLocation[A], ov: A, nv: A)
}

abstract class KCASSpec extends BaseSpecA { this: KCASImplSpec =>

  import KCASSpec._

  private final def tryPerformBatch(ops: List[CASD[_]]): Boolean = {
    val ctx = kcasImpl.currentContext()
    val desc = ops.foldLeft(kcasImpl.start(ctx)) { (d, op) =>
      op match {
        case op: CASD[a] =>
          kcasImpl.addCas(d, op.address, op.ov, op.nv, ctx)
      }
    }
    kcasImpl.tryPerform(desc, kcasImpl.currentContext())
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
    assertSameInstance(kcasImpl.read(r1, ctx), "x")
    assertSameInstance(kcasImpl.read(r2, ctx), "y")
    assertSameInstance(kcasImpl.read(r3, ctx), "z")
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

    r1.unsafeSetVolatile("x")
    assert(!go())
    val ctx = kcasImpl.currentContext()
    assertSameInstance(kcasImpl.read(r1, ctx), "x")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3")

    r1.unsafeSetVolatile("r1")
    r2.unsafeSetVolatile("x")
    assert(!go())
    assertSameInstance(kcasImpl.read(r1, ctx), "r1")
    assertSameInstance(kcasImpl.read(r2, ctx), "x")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3")

    r2.unsafeSetVolatile("r2")
    r3.unsafeSetVolatile("x")
    assert(!go())
    assertSameInstance(kcasImpl.read(r1, ctx), "r1")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "x")

    r3.unsafeSetVolatile("r3")
    assert(go())
    assertSameInstance(kcasImpl.read(r1, ctx), "x")
    assertSameInstance(kcasImpl.read(r2, ctx), "y")
    assertSameInstance(kcasImpl.read(r3, ctx), "z")
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
    assertSameInstance(kcasImpl.read(r1, ctx), "r1")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
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
    assertSameInstance(kcasImpl.read(r1, ctx), "x2")
    assertSameInstance(kcasImpl.read(r2, ctx), "y2")
    assertSameInstance(kcasImpl.read(r3, ctx), "z2")
  }

  test("Snapshotting should work") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = kcasImpl.start(ctx)
    val d1 = kcasImpl.addCas(d0, r1, "r1", "r1x", ctx)

    val snap = kcasImpl.snapshot(d1, ctx)
    val d21 = kcasImpl.addCas(d1, r2, "foo", "bar", ctx)
    assert(!kcasImpl.tryPerform(d21, ctx))
    assertSameInstance(kcasImpl.read(r1, ctx), "r1")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3")
    val d22 = kcasImpl.addCas(snap, r3, "r3", "r3x", ctx)
    assert(kcasImpl.tryPerform(d22, ctx))
    assertSameInstance(kcasImpl.read(r1, ctx), "r1x")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3x")
  }

  test("Snapshotting should work when cancelling") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val ctx = kcasImpl.currentContext()
    val d0 = kcasImpl.start(ctx)
    val d1 = kcasImpl.addCas(d0, r1, "r1", "r1x", ctx)
    val snap = kcasImpl.snapshot(d1, ctx)
    kcasImpl.addCas(d1, r2, "foo", "bar", ctx) // unused
    assertSameInstance(kcasImpl.read(r1, ctx), "r1")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3")
    val d22 = kcasImpl.addCas(snap, r3, "r3", "r3x", ctx)
    assert(kcasImpl.tryPerform(d22, ctx))
    assertSameInstance(kcasImpl.read(r1, ctx), "r1x")
    assertSameInstance(kcasImpl.read(r2, ctx), "r2")
    assertSameInstance(kcasImpl.read(r3, ctx), "r3x")
  }
}
