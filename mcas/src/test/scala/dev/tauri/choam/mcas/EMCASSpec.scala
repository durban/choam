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

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch }

import scala.runtime.VolatileObjectRef

import cats.syntax.eq._
import dev.tauri.choam.mcas.MemoryLocation

class EMCASSpec extends BaseSpecA {

  test("EMCAS should allow null as ov or nv") {
    val r1 = MemoryLocation.unsafe[String](null)
    val r2 = MemoryLocation.unsafe[String]("x")
    val ctx = EMCAS.currentContext()
    val desc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, null, "x", ctx), r2, "x", null, ctx)
    val snap = EMCAS.snapshot(desc, ctx)
    assert(EMCAS.tryPerform(desc, ctx))
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq null)
    assert(!EMCAS.tryPerform(snap, ctx))
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq null)
  }

  test("EMCAS should clean up finalized descriptors") {
    val r1 = MemoryLocation.unsafe[String]("x")
    val r2 = MemoryLocation.unsafe[String]("y")
    val ctx = EMCAS.currentContext()
    val desc = EMCAS.addCas(EMCAS.start(ctx), r1, "x", "a", ctx)
    val snap = EMCAS.snapshot(desc, ctx)
    assert(EMCAS.tryPerform(EMCAS.addCas(desc, r2, "y", "b", ctx), ctx))
    assert(EMCAS.read(r1, ctx) eq "a")
    assert(EMCAS.read(r2, ctx) eq "b")
    assert(EMCAS.spinUntilCleanup(r1) eq "a")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeGetVolatile() eq "a")
    assert(r2.unsafeGetVolatile() eq "b")
    assert(r1.unsafeCasVolatile("a", "x")) // reset
    val desc2 = snap
    assert(!EMCAS.tryPerform(EMCAS.addCas(desc2, r2, "y", "b", ctx), ctx)) // this will fail
    assert(EMCAS.read(r1, ctx) eq "x")
    assert(EMCAS.read(r2, ctx) eq "b")
    assert(EMCAS.spinUntilCleanup(r1) eq "x")
    assert(EMCAS.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeGetVolatile() eq "x")
    assert(r2.unsafeGetVolatile() eq "b")
  }

  test("EMCAS should clean up finalized descriptors if the original thread releases them") {
    val r1 = MemoryLocation.unsafe[String]("x")
    val r2 = MemoryLocation.unsafe[String]("y")
    var ok = false
    val t = new Thread(() => {
      val ctx = EMCAS.currentContext()
      ok = EMCAS.tryPerform(EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "x", "a", ctx), r2, "y", "b", ctx), ctx)
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

  test("EMCAS op should be finalizable even if a thread dies mid-op") {
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(0L, 0L, 0L, 1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var descT1: WordDescriptor[_] = null
    val t1 = new Thread(() => {
      val ctx = EMCAS.currentContext()
      val hDesc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "x", "a", ctx), r2, "y", "b", ctx)
      ctx.startOp()
      val desc = hDesc.prepare(ctx)
      val d0 = desc.wordIterator().next().asInstanceOf[WordDescriptor[String]]
      assert(d0.address eq r1)
      r1.unsafeSetVolatile(d0.castToData)
      descT1 = d0
      latch1.countDown()
      latch2.await()
      // and the thread dies here, with an active CAS
    })
    t1.start()
    latch1.await()
    val ctx = EMCAS.currentContext()
    assert(ctx.isInUseByOther(descT1))
    latch2.countDown()
    t1.join()
    assert(!t1.isAlive())
    while (EMCAS.global.snapshotReservations(t1.getId()).get() ne null) {
      System.gc()
    }

    val succ = EMCAS.tryPerform(EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "x", "x2", ctx), r2, "y", "y2", ctx), ctx)
    assert(!succ)
    assert(EMCAS.read(r1, ctx) eq "a")
    assert(EMCAS.read(r2, ctx) eq "b")
    EMCAS.spinUntilCleanup(r1)
    EMCAS.spinUntilCleanup(r2)
    assert(clue(r1.unsafeGetVolatile()) eq "a")
    assert(clue(r2.unsafeGetVolatile()) eq "b")
    assert(!ctx.isInUseByOther(descT1))
  }

  test("EMCAS should not replace and forget active descriptors") {
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(0L, 0L, 0L, 1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val t1 = new Thread(() => {
      val ctx = EMCAS.currentContext()
      val hDesc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "x", "a", ctx), r2, "y", "b", ctx)
      ctx.startOp()
      val desc = hDesc.prepare(ctx)
      try {
        val d0 = desc.wordIterator().next().asInstanceOf[WordDescriptor[String]]
        assert(d0.address eq r1)
        assert(d0.address.unsafeCasVolatile(d0.ov, d0.castToData))
        // and the thread pauses here, with an active CAS
        latch1.countDown()
        latch2.await()
      } finally ctx.endOp()
    })
    t1.start()
    latch1.await()

    var ok = false
    val t2 = new Thread(() => {
      // the other thread changes back the values (but first finalizes the active op):
      val ctx = EMCAS.currentContext()
      val desc = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r2, "b", "y", ctx), r1, "a", "x", ctx)
      assert(EMCAS.tryPerform(desc, ctx))
      // wait for descriptors to be collected:
      assert(EMCAS.spinUntilCleanup(r2, max = 0x100000L) eq null)
      assert(EMCAS.spinUntilCleanup(r1, max = 0x100000L) eq null)
      ok = true
    })
    t2.start()
    t2.join()
    assert(ok)
    latch2.countDown()
    t1.join()
  }

  test("EMCAS should extend the interval of a new descriptor if it replaces an old one") {
    testExtendInterval()
  }

  @tailrec
  private def testExtendInterval(): Unit = {
    // we never want cleanup during this test, so
    // we configure it with a very-very low probability:
    val neverReplace = (1 << 31)
    val r = MemoryLocation.unsafe("x")
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val ctx = EMCAS.currentContext()
    ctx.resetCounter(to = 0)
    var threadEpoch: Option[Long] = None
    val t = new Thread(() => {
      val ctx = EMCAS.currentContext()
      ctx.forceNextEpoch()
      ctx.startOp()
      try {
        threadEpoch = Some(ctx.globalContext.epochNumber)
        latch1.countDown()
        latch2.await()
      } finally ctx.endOp()
    })
    t.start()
    latch1.await()
    val tEpoch = threadEpoch.getOrElse(fail("no threadEpoch"))
    val hDescOld = EMCAS.addCas(EMCAS.start(ctx), r, "x", "y", ctx)
    val descOld = ctx.op {
      val descOld = hDescOld.prepare(ctx)
      assert(EMCAS.MCAS(desc = descOld, ctx = ctx, replace = neverReplace))
      descOld
    }
    val oldEpoch = descOld.wordIterator().next().getMinEpochVolatile()
    if (oldEpoch =!= tEpoch) {
      // This may happen with a low probability,
      // in which case we restart the whole test case,
      // because it depends on `t` reserving `oldEpoch`:
      testExtendInterval()
    } else {
      // Ok, we can continue the test case:
      assertSameInstance(r.asInstanceOf[MemoryLocation[Any]].unsafeGetVolatile(), descOld.wordIterator().next())
      ctx.forceNextEpoch()
      ctx.resetCounter(to = 0)
      val hDescNew = EMCAS.addCas(EMCAS.start(ctx), r, "y", "z", ctx)
      val descNew = ctx.op {
        val descNew = hDescNew.prepare(ctx)
        val newEpoch = descNew.wordIterator().next().getMinEpochVolatile()
        assert(clue(newEpoch) > clue(oldEpoch))
        assert(EMCAS.MCAS(desc = descNew, ctx = ctx, replace = neverReplace))
        descNew
      }
      assertSameInstance(r.asInstanceOf[MemoryLocation[Any]].unsafeGetVolatile(), descNew.wordIterator().next())
      assert(ctx.isInUseByOther(descNew.wordIterator().next().cast))
      latch2.countDown()
      t.join()
      assert(!ctx.isInUseByOther(descNew.wordIterator().next().cast))
    }
  }

  test("EMCAS read should help the other operation") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 42L)
    val ctx = EMCAS.currentContext()
    val hOther: HalfEMCASDescriptor = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "r1", "x", ctx), r2, "r2", "y", ctx)
    val other = ctx.op { hOther.prepare(ctx) }
    val d0 = other.wordIterator().next().asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSetVolatile(d0.castToData)
    val res = EMCAS.read(r1, ctx)
    assertEquals(res, "x")
    assertEquals(EMCAS.read(r1, ctx), "x")
    assertEquals(EMCAS.read(r2, ctx), "y")
    assert(other.getStatus() eq EMCASStatus.SUCCESSFUL)
  }

  test("EMCAS read should roll back the other op if necessary") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 99L)
    val ctx = EMCAS.currentContext()
    val hOther = EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), r1, "r1", "x", ctx), r2, "zzz", "y", ctx)
    val other = ctx.op { hOther.prepare(ctx) }
    val d0 = other.wordIterator().next().asInstanceOf[WordDescriptor[String]]
    assert(d0.address eq r1)
    r1.unsafeSetVolatile(d0.castToData)
    val res = EMCAS.read(r1, ctx)
    assertEquals(res, "r1")
    assertEquals(EMCAS.read(r1, ctx), "r1")
    assertEquals(EMCAS.read(r2, ctx), "r2")
  }

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

  test("Descriptors should be sorted") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L, 0L, 0L, 1L)
    val r2 = MemoryLocation.unsafeWithId("r2")(0L, 0L, 0L, 2L)
    val r3 = MemoryLocation.unsafeWithId("r3")(0L, 0L, 0L, 3L)
    val ctx = EMCAS.currentContext()
    val d0 = EMCAS.start(ctx)
    val d1 = EMCAS.addCas(d0, r1, "r1", "A", ctx)
    val d2 = EMCAS.addCas(d1, r3, "r3", "C", ctx)
    val d3 = EMCAS.addCas(d2, r2, "r2", "B", ctx)
    val d = ctx.op { d3.prepare(ctx) }
    val it = d.wordIterator()
    assertSameInstance(it.next().address, r1)
    assertSameInstance(it.next().address, r2)
    assertSameInstance(it.next().address, r3)
    assert(!it.hasNext())
  }
}
