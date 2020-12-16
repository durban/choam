/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

abstract class KCASSpec extends BaseSpec {

  private final def tryPerformBatch(ops: List[CASD[_]]): Boolean = {
    val desc = ops.foldLeft(kcasImpl.start()) { (d, op) =>
      op match {
        case op: CASD[a] =>
          d.withCAS[a](op.ref, op.ov, op.nv)
      }
    }
    desc.tryPerform()
  }

  "k-CAS" should "succeed if old values match, and there is no contention" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val r3 = Ref.mk("r3")
    val succ = tryPerformBatch(List(
      CASD(r1, "r1", "x"),
      CASD(r2, "r2", "y"),
      CASD(r3, "r3", "z")
    ))
    assert(succ)
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("x")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("y")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("z")
  }

  it should "fail if any of the old values doesn't match" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val r3 = Ref.mk("r3")

    def go(): Boolean = {
      tryPerformBatch(List(
        CASD(r1, "r1", "x"),
        CASD(r2, "r2", "y"),
        CASD(r3, "r3", "z")
      ))
    }

    r1.unsafeSet("x")
    assert(!go())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("x")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3")

    r1.unsafeSet("r1")
    r2.unsafeSet("x")
    assert(!go())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("x")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3")

    r2.unsafeSet("r2")
    r3.unsafeSet("x")
    assert(!go())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("x")

    r3.unsafeSet("r3")
    assert(go())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("x")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("y")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("z")
  }

  it should "not accept more than one CAS for the same ref" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val exc = intercept[Exception] {
      tryPerformBatch(List(
        CASD(r1, "r1", "x"),
        CASD(r2, "r2", "y"),
        CASD(r1, "r1", "x") // this is a duplicate
      ))
    }
    exc.getMessage should include ("Impossible k-CAS")
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
  }

  it should "be able to succeed after one successful operation" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val r3 = Ref.mk("r3")

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

    kcasImpl.read(r1) shouldBe theSameInstanceAs ("x2")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("y2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("z2")
  }

  "Snapshotting" should "work" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val r3 = Ref.mk("r3")
    val d0 = kcasImpl.start()
    val d1 = d0.withCAS(r1, "r1", "r1x")
    val snap = d1.snapshot()
    val d21 = d1.withCAS(r2, "foo", "bar")
    assert(!d21.tryPerform())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3")
    val d22 = snap.load().withCAS(r3, "r3", "r3x")
    assert(d22.tryPerform())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1x")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3x")
  }

  it should "work when cancelling" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val r3 = Ref.mk("r3")
    val d0 = kcasImpl.start()
    val d1 = d0.withCAS(r1, "r1", "r1x")
    val snap = d1.snapshot()
    val d21 = d1.withCAS(r2, "foo", "bar")
    d21.cancel()
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3")
    val d22 = snap.load().withCAS(r3, "r3", "r3x")
    assert(d22.tryPerform())
    kcasImpl.read(r1) shouldBe theSameInstanceAs ("r1x")
    kcasImpl.read(r2) shouldBe theSameInstanceAs ("r2")
    kcasImpl.read(r3) shouldBe theSameInstanceAs ("r3x")
  }
}

final class KCASSpecNaiveKCAS
  extends KCASSpec
  with SpecNaiveKCAS

final class KCASSpecEMCAS
  extends KCASSpec
  with SpecEMCAS
