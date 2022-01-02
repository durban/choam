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

import java.lang.ref.{ Reference, WeakReference }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch }

import scala.runtime.VolatileObjectRef

import mcas.MemoryLocation

class EMCASSpec extends BaseSpecA {

  // OK:
  test("EMCAS should allow null as ov or nv") {
    val r1 = MemoryLocation.unsafe[String](null)
    val r2 = MemoryLocation.unsafe[String]("x")
    val ctx = EMCAS.currentContext()
    val desc = ctx.addCas(ctx.addCas(ctx.start(), r1, null, "x"), r2, "x", null)
    val snap = ctx.snapshot(desc)
    assert(EMCAS.tryPerform(desc, ctx))
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq null)
    assert(!EMCAS.tryPerform(snap, ctx))
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq null)
  }

  // OK, but slow:
  test("EMCAS should clean up finalized descriptors") {
    val r1 = MemoryLocation.unsafe[String]("x")
    val r2 = MemoryLocation.unsafe[String]("y")
    val ctx = EMCAS.currentContext()
    val desc = ctx.addCas(ctx.start(), r1, "x", "a")
    val snap = ctx.snapshot(desc)
    assert(ctx.tryPerform(ctx.addCas(desc, r2, "y", "b")))
    assert(EMCAS.read(r1, ctx) eq "a")
    assert(EMCAS.read(r2, ctx) eq "b")
    assert(EMCAS.spinUntilCleanup(r1) eq "a")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeGetVolatile() eq "a")
    assert(r2.unsafeGetVolatile() eq "b")
    assert(r1.unsafeCasVolatile("a", "x")) // reset
    val desc2 = snap
    assert(!ctx.tryPerform(ctx.addCas(desc2, r2, "y", "b"))) // this will fail
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq "b")
    assert(EMCAS.spinUntilCleanup(r1) eq "x")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeGetVolatile() eq "x")
    assert(r2.unsafeGetVolatile() eq "b")
  }

  // OK, but slow:
  test("EMCAS should clean up finalized descriptors if the original thread releases them") {
    val r1 = MemoryLocation.unsafe[String]("x")
    val r2 = MemoryLocation.unsafe[String]("y")
    var ok = false
    val t = new Thread(() => {
      val ctx = EMCAS.currentContext()
      ok = ctx.tryPerform(ctx.addCas(ctx.addCas(ctx.start(), r1, "x", "a"), r2, "y", "b"))
    })
    @tailrec
    def checkCleanup(ref: MemoryLocation[String], old: String, exp: String): Boolean = {
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

  // OK, but very slow:
  test("EMCAS op should be finalizable even if a thread dies mid-op") {
    threadDeathTest(runGcBetween = false, finishWithAnotherOp = true)
    threadDeathTest(runGcBetween = false, finishWithAnotherOp = false)
    threadDeathTest(runGcBetween = true, finishWithAnotherOp = true)
    threadDeathTest(runGcBetween = true, finishWithAnotherOp = false)
  }

  def threadDeathTest(runGcBetween: Boolean, finishWithAnotherOp: Boolean): Unit = {
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(0L, 0L, 0L, 1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var weakMark: WeakReference[AnyRef] = null
    val t1 = new Thread(() => {
      val ctx = EMCAS.currentContext()
      val hDesc = ctx.addCas(ctx.addCas(ctx.start(), r1, "x", "a"), r2, "y", "b")
      val desc = EMCASDescriptor.prepare(hDesc)
      val d0 = desc.wordIterator().next().asInstanceOf[WordDescriptor[String]]
      val mark = new McasMarker
      assert(d0.address eq r1)
      r1.unsafeSetVolatile(d0.castToData)
      assert(r1.unsafeCasMarkerVolatile(null, new WeakReference(mark)))
      weakMark = new WeakReference(mark)
      latch1.countDown()
      latch2.await()
      // and the thread dies here, with an active CAS
      Reference.reachabilityFence(mark)
    })
    t1.start()
    latch1.await()
    latch2.countDown()
    t1.join()
    assert(!t1.isAlive())
    assert(weakMark ne null)

    if (runGcBetween) {
      // make sure the marker is collected:
      while (weakMark.get() ne null) {
        System.gc()
      }
    }

    val ctx = EMCAS.currentContext()
    if (finishWithAnotherOp) {
      // run another op; this should
      // finalize the previous one:
      val succ = ctx.tryPerform(ctx.addCas(ctx.addCas(ctx.start(), r1, "x", "x2"), r2, "y", "y2"))
      assert(!succ)
    }
    // else: only run readValue; this should
    // also finalize the previous op:

    val read1 = EMCAS.read(r1, ctx)
    assert(read1 eq "a")
    assert(EMCAS.read(r2, ctx) eq "b")
    EMCAS.spinUntilCleanup(r1)
    EMCAS.spinUntilCleanup(r2)
    assert(clue(r1.unsafeGetVolatile()) eq "a")
    assert(clue(r2.unsafeGetVolatile()) eq "b")
    assert(weakMark.get() eq null)
  }

  // OK, but slow:
  test("EMCAS should not simply replace  active descriptors (mark should be handled)") {
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(0L, 0L, 0L, 1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var ok0 = false
    val t1 = new Thread(() => {
      val ctx = EMCAS.currentContext()
      val hDesc = ctx.addCas(ctx.addCas(ctx.start(), r1, "x", "a"), r2, "y", "b")
      val desc = EMCASDescriptor.prepare(hDesc)
      val d0 = desc.wordIterator().next().asInstanceOf[WordDescriptor[String]]
      assert(d0.address eq r1)
      assert(d0.address.unsafeCasVolatile(d0.ov, d0.castToData))
      val mark = new McasMarker
      assert(d0.address.unsafeCasMarkerVolatile(null, new WeakReference(mark)))
      // and the thread pauses here, with an active CAS
      latch1.countDown()
      latch2.await()
      Reference.reachabilityFence(mark)
      ok0 = true
    })
    t1.start()
    latch1.await()

    var ok = false
    val t2 = new Thread(() => {
      // the other thread changes back the values (but first finalizes the active op):
      val ctx = EMCAS.currentContext()
      val desc = ctx.addCas(ctx.addCas(ctx.start(), r2, "b", "y"), r1, "a", "x")
      assert(EMCAS.tryPerform(desc, ctx))
      // wait for descriptors to be collected:
      assertEquals(clue(EMCAS.spinUntilCleanup[String](r2)), "y")
      // but this one shouldn't be collected, as the other thread holds the mark of `d0`:
      assert(EMCAS.spinUntilCleanup(r1, max = 0x2000L) eq null)
      assert(r1.unsafeGetMarkerVolatile().get() ne null)
      ok = true
    })
    t2.start()
    t2.join()
    assert(ok)
    latch2.countDown()
    t1.join()
    assert(ok0)

    // t1 released the mark, now it should be replaced:
    assertEquals(clue(EMCAS.spinUntilCleanup[String](r1)), "x")
  }

  // OK:
  test("EMCAS read should help the other operation") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 42L)
    val ctx = EMCAS.currentContext()
    val hOther: HalfEMCASDescriptor = ctx.addCas(ctx.addCas(ctx.start(), r1, "r1", "x"), r2, "r2", "y")
    val other = EMCASDescriptor.prepare(hOther)
    val d0 = other.wordIterator().next().asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSetVolatile(d0.castToData)
    val mark = new McasMarker
    assert(r1.unsafeCasMarkerVolatile(null, new WeakReference(mark)))
    val res = EMCAS.read(r1, ctx)
    assertEquals(res, "x")
    assertEquals(EMCAS.read(r1, ctx), "x")
    assertEquals(EMCAS.read(r2, ctx), "y")
    assert(other.getStatus() eq EMCASStatus.SUCCESSFUL)
    // we hold a strong ref, since we're pretending we're another op
    Reference.reachabilityFence(mark)
  }

  // OK:
  test("EMCAS read should roll back the other op if necessary") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 99L)
    val ctx = EMCAS.currentContext()
    val hOther = ctx.addCas(ctx.addCas(ctx.start(), r1, "r1", "x"), r2, "zzz", "y")
    val other = EMCASDescriptor.prepare(hOther)
    val d0 = other.wordIterator().next().asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSetVolatile(d0.castToData)
    val mark = new McasMarker
    assert(r1.unsafeCasMarkerVolatile(null, new WeakReference(mark)))
    val res = EMCAS.read(r1, ctx)
    assertEquals(res, "r1")
    assertEquals(EMCAS.read(r1, ctx), "r1")
    assertEquals(EMCAS.read(r2, ctx), "r2")
    assert(other.getStatus() eq EMCASStatus.FAILED)
    // we hold a strong ref, since we're pretending we're another op
    Reference.reachabilityFence(mark)
  }

  // OK:
  test("ThreadContexts should be thread-local") {
    val N = 10000
    val tc1 = new ConcurrentLinkedQueue[ThreadContext]
    val tc2 = new ConcurrentLinkedQueue[ThreadContext]
    val tsk = (tc: ConcurrentLinkedQueue[ThreadContext]) => {
      tc.offer(EMCAS.currentContext())
      Thread.sleep(10L)
      for (_ <- 1 to N) {
        tc.offer(EMCAS.currentContext())
      }
    }
    val t1 = new Thread(() => { tsk(tc1) })
    val t2 = new Thread(() => { tsk(tc2) })
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    assertEquals(tc1.size(), N + 1)
    assertEquals(tc2.size(), N + 1)
    val fCtx1 = tc1.poll()
    assert(fCtx1 ne null)
    val fCtx2 = tc2.poll()
    assert(fCtx2 ne null)
    for (_ <- 1 to N) {
      val ctx1 = tc1.poll()
      assertSameInstance(ctx1, fCtx1)
      val ctx2 = tc2.poll()
      assertSameInstance(ctx2, fCtx2)
      assert(ctx1 ne ctx2)
    }
  }

  // OK:
  test("ThreadContexts should work even if thread IDs are reused") {
    final class TrickyThread(ref: VolatileObjectRef[ThreadContext]) extends Thread {
      final override def getId(): Long = 42L
      final override def run(): Unit = {
        ref.elem = EMCAS.currentContext()
      }
    }
    val r1 = VolatileObjectRef.create(nullOf[ThreadContext])
    val t1 = new TrickyThread(r1)
    t1.start()
    t1.join()
    val r2 = VolatileObjectRef.create(nullOf[ThreadContext])
    val t2 = new TrickyThread(r2)
    t2.start()
    t2.join()
    assert(r1.elem ne r2.elem)
    assertEquals(r1.elem.tid, r2.elem.tid)
  }

  // OK:
  test("Descriptors should be sorted") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 1L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 2L)
    val r3 = MemoryLocation.unsafeWithId("r3")(0L, 0L, 0L, 3L)
    val ctx = EMCAS.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCas(d0, r1, "r1", "A")
    val d2 = ctx.addCas(d1, r3, "r3", "C")
    val d3 = ctx.addCas(d2, r2, "r2", "B")
    val d = EMCASDescriptor.prepare(d3)
    val it = d.wordIterator()
    assertSameInstance(it.next().address, r1)
    assertSameInstance(it.next().address, r2)
    assertSameInstance(it.next().address, r3)
    assert(!it.hasNext())
  }
}
