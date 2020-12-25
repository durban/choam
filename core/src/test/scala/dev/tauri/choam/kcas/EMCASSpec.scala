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

class EMCASSpec extends BaseSpecA {

  sealed trait Obj
  final case object A extends Obj
  final case object B extends Obj
  final case object C extends Obj

  test("EMCAS should allow null as ov or nv") {
    val r1 = Ref.mk[String](null)
    val r2 = Ref.mk[String]("x")
    val desc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, null, "x"), r2, "x", null)
    val snap = EMCAS.snapshot(desc)
    assert(EMCAS.tryPerform(desc))
    assert(EMCAS.read(r1) eq "x")
    assert(EMCAS.read(r2) eq null)
    assert(!EMCAS.tryPerform(snap))
    assert(EMCAS.read(r1) eq "x")
    assert(EMCAS.read(r2) eq null)
  }

  test("EMCAS should clean up finalized descriptors") {
    val r1 = Ref.mk[String]("x")
    val r2 = Ref.mk[String]("y")
    var desc = EMCAS.addCas(EMCAS.start(), r1, "x", "a")
    var snap = EMCAS.snapshot(desc)
    assert(EMCAS.tryPerform(EMCAS.addCas(desc, r2, "y", "b")))
    assert(EMCAS.read(r1) eq "a")
    assert(EMCAS.read(r2) eq "b")
    desc = null
    assert(EMCAS.spinUntilCleanup(r1) eq "a")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeTryRead() eq "a")
    assert(r2.unsafeTryRead() eq "b")
    assert(r1.unsafeTryPerformCas("a", "x")) // reset
    var desc2 = snap
    assert(!EMCAS.tryPerform(EMCAS.addCas(desc2, r2, "y", "b"))) // this will fail
    assert(EMCAS.read(r1) eq "x")
    assert(EMCAS.read(r2) eq "b")
    snap = null
    desc2 = null
    assert(EMCAS.spinUntilCleanup(r1) eq "x")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeTryRead() eq "x")
    assert(r2.unsafeTryRead() eq "b")
  }

  test("EMCAS should clean up finalized descriptors if the original thread releases them") {
    val r1 = Ref.mk[String]("x")
    val r2 = Ref.mk[String]("y")
    @volatile var ok = false
    val t = new Thread(() => {
      ok = EMCAS.tryPerform(EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, "x", "a"), r2, "y", "b"))
    })
    @tailrec
    def checkCleanup(ref: Ref[String], old: String, exp: String): Boolean = {
      EMCAS.spinUntilCleanup(ref) match {
        case s if s == old =>
          // CAS not started yet, retry
          checkCleanup(ref, old, exp)
        case s if s == exp =>
          // descriptor have been cleaned up:
          true
        case _ =>
          // mustn't happen:
          false
      }
    }
    var ok1 = false
    val c1 = new Thread(() => { ok1 = checkCleanup(r1, "x", "a") })
    var ok2 = false
    val c2 = new Thread(() => { ok2 = checkCleanup(r2, "y", "b") })
    t.start()
    c1.start()
    c2.start()
    t.join()
    c1.join()
    c2.join()
    assert(ok)
    assert(ok1)
    assert(ok2)
  }

  test("EMCAS should be finalizable even if a thread dies mid-op".ignore) {
    val r1 = Ref.mkWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId[String]("y")(0L, 0L, 0L, 1L)
    val t1 = new Thread(() => {
      val desc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, "x", "a"), r2, "y", "b")
      desc.sort()
      val d0 = desc.words.get(0).asInstanceOf[WordDescriptor[String]]
      assert(d0.address eq r1)
      r1.unsafeSet(d0.holder.castToData())
      // and the thread dies here, with an active CAS
    })
    t1.start()
    t1.join()
    System.gc()
    val succ = EMCAS.tryPerform(EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, "x", "x2"), r2, "y", "y2"))
    assert(!succ)
    System.gc()
    assert(EMCAS.read(r1) eq "a")
    assert(EMCAS.read(r2) eq "b")
    assert(r1.unsafeTryRead() eq "a")
    assert(r2.unsafeTryRead() eq "b")
  }

  test("EMCAS read should help the other operation") {
    val r1 = Ref.mkWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId("r2")(0L, 0L, 0L, 42L)
    val other: EMCASDescriptor = EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, "r1", "x"), r2, "r2", "y")
    other.sort()
    val d0 = other.words.get(0).asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSet(d0.holder.castToData())
    val res = EMCAS.read(r1)
    assertEquals(res, "x")
    assertEquals(EMCAS.read(r1), "x")
    assertEquals(EMCAS.read(r2), "y")
    assert(other.getStatus() eq EMCASStatus.SUCCESSFUL)
  }

  test("EMCAS read should roll back the other op if necessary") {
    val r1 = Ref.mkWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId("r2")(0L, 0L, 0L, 99L)
    val other = EMCAS.addCas(EMCAS.addCas(EMCAS.start(), r1, "r1", "x"), r2, "zzz", "y")
    other.sort()
    val d0 = other.words.get(0).asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSet(d0.holder.castToData())
    val res = EMCAS.read(r1)
    assertEquals(res, "r1")
    assertEquals(EMCAS.read(r1), "r1")
    assertEquals(EMCAS.read(r2), "r2")
  }
}
