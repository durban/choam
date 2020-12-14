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

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalactic.TypeCheckedTripleEquals

class EMCASSpec
  extends AnyFlatSpec
  with Matchers
  with TypeCheckedTripleEquals {

  sealed trait Obj
  final case object A extends Obj
  final case object B extends Obj
  final case object C extends Obj

  implicit val ec: ExecutionContext =
    ExecutionContext.global

  private def polluteTheHeap[A](desc: AnyRef): A =
    desc.asInstanceOf[A]

  "EMCAS" should "allow null as ov or nv" in {
    val r1 = Ref.mk[String](null)
    val r2 = Ref.mk[String]("x")
    val desc = EMCAS
      .start()
      .withCAS(r1, null, "x")
      .withCAS(r2, "x", null)
    val snap = desc.snapshot()
    assert(desc.tryPerform())
    assert(EMCAS.tryReadOne(r1) eq "x")
    assert(EMCAS.tryReadOne(r2) eq null)
    assert(!snap.load().tryPerform())
    assert(EMCAS.tryReadOne(r1) eq "x")
    assert(EMCAS.tryReadOne(r2) eq null)
  }

  it should "clean up finalized descriptors" in {
    val r1 = Ref.mk[String]("x")
    val r2 = Ref.mk[String]("y")
    var desc = EMCAS
      .start()
      .withCAS(r1, "x", "a")
    var snap = desc.snapshot()
    assert(desc.withCAS(r2, "y", "b").tryPerform())
    assert(EMCAS.tryReadOne(r1) eq "a")
    assert(EMCAS.tryReadOne(r2) eq "b")
    desc = null
    System.gc() // TODO: this makes the test non-deterministic
    EMCAS.tryReadOne(r1) // this read should replace the desc with the final value
    EMCAS.tryReadOne(r2) // this read should replace the desc with the final value
    assert(r1.unsafeTryRead() eq "a")
    assert(r2.unsafeTryRead() eq "b")
    assert(r1.unsafeTryPerformCas("a", "x")) // reset
    var desc2 = snap.load()
    assert(!desc2.withCAS(r2, "y", "b").tryPerform()) // this will fail
    assert(EMCAS.tryReadOne(r1) eq "x")
    assert(EMCAS.tryReadOne(r2) eq "b")
    snap = null
    desc2 = null
    System.gc() // TODO: this makes the test non-deterministic
    EMCAS.tryReadOne(r1) // this read should replace the desc with the final value
    EMCAS.tryReadOne(r2) // this read should replace the desc with the final value
    assert(r1.unsafeTryRead() eq "x")
    assert(r2.unsafeTryRead() eq "b")
  }

  it should "clean up finalized descriptors if the original thread releases them" in {
    val r1 = Ref.mk[String]("x")
    val r2 = Ref.mk[String]("y")
    var ok = false
    val t = new Thread(() => {
      ok = EMCAS
        .start()
        .withCAS(r1, "x", "a")
        .withCAS(r2, "y", "b")
        .tryPerform()
    })
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

  it should "be finalizable even if a thread dies mid-op" ignore {
    val r1 = Ref.mkWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId[String]("y")(0L, 0L, 0L, 1L)
    val t1 = new Thread(() => {
      val desc = EMCAS
        .start()
        .withCAS(r1, "x", "a")
        .withCAS(r2, "y", "b")
        .asInstanceOf[EMCAS.MCASDescriptor]
      desc.sort()
      val d0 = desc.words.get(0)
      assert(d0.address eq r1)
      r1.unsafeSet(polluteTheHeap[String](d0.holder))
      // and the thread dies here, with an active CAS
    })
    t1.start()
    t1.join()
    System.gc()
    val succ = EMCAS
      .start()
      .withCAS(r1, "x", "x2")
      .withCAS(r2, "y", "y2")
      .tryPerform()
    assert(!succ)
    System.gc()
    assert(EMCAS.tryReadOne(r1) eq "a")
    assert(EMCAS.tryReadOne(r2) eq "b")
    assert(r1.unsafeTryRead() eq "a")
    assert(r2.unsafeTryRead() eq "b")
  }

  "EMCAS Read" should "help the other operation" in {
    val r1 = Ref.mkWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId("r2")(0L, 0L, 0L, 42L)
    val other: EMCAS.MCASDescriptor = EMCAS
      .start()
      .withCAS(r1, "r1", "x")
      .withCAS(r2, "r2", "y")
      .asInstanceOf[EMCAS.MCASDescriptor]
    other.sort()
    val d0 = other.words.get(0)
    assert(d0.address eq r1)
    r1.unsafeSet(polluteTheHeap[String](d0.holder))
    val res = EMCAS.tryReadOne(r1)
    res should === ("x")
    EMCAS.tryReadOne(r1) should === ("x")
    EMCAS.tryReadOne(r2) should === ("y")
    assert(other.getStatus() eq EMCASStatus.SUCCESSFUL)
  }

  it should "roll back the other op if necessary" in {
    val r1 = Ref.mkWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = Ref.mkWithId("r2")(0L, 0L, 0L, 99L)
    val other = EMCAS
      .start()
      .withCAS(r1, "r1", "x")
      .withCAS(r2, "zzz", "y") // this will fail
      .asInstanceOf[EMCAS.MCASDescriptor]
    other.sort()
    val d0 = other.words.get(0)
    assert(d0.address eq r1)
    r1.unsafeSet(polluteTheHeap[String](d0.holder))
    val res = EMCAS.tryReadOne(r1)
    res should === ("r1")
    EMCAS.tryReadOne(r1) should === ("r1")
    EMCAS.tryReadOne(r2) should === ("r2")
  }
}
