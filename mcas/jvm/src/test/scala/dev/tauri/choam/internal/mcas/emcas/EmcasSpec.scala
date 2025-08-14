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
package emcas

import java.lang.ref.{ Reference, WeakReference }
import java.util.concurrent.{ ConcurrentLinkedQueue, ConcurrentSkipListSet, CountDownLatch, ThreadLocalRandom }

import scala.concurrent.duration._
import scala.runtime.VolatileObjectRef

import cats.syntax.all._

import munit.{ Location, TestOptions }

// TODO: all tests in `choam-mcas` are executed with
// TODO: `SimpleMemoryLocation`; we should run them
// TODO: with actual `Ref`s too (or instead?)

class EmcasSpec extends BaseSpec { // TODO: move this to jvm-native

  private[this] val inst: Emcas =
    new Emcas(this.osRngInstance, java.lang.Runtime.getRuntime().availableProcessors())

  private[this] def rigInstance: RefIdGen =
    this.inst.currentContext().refIdGen

  final override def afterAll(): Unit = {
    this.inst.close()
    super.afterAll()
  }

  final override def munitTimeout: Duration =
    5.minutes

  final override def test(options: TestOptions)(body: => Any)(implicit loc: Location): Unit = {
    def wrappedBody(): Any = {
      println(s"Starting '${options.name}'")
      val res: Any = body
      println(s"Finished '${options.name}'")
      res
    }
    super.test(options)(wrappedBody())
  }

  test("EMCAS should allow null as ov or nv") {
    val r1 = MemoryLocation.unsafeUnpadded[String](null, this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val ctx = inst.currentContextInternal()
    val desc = ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), r1, null, "x"), r2, "x", null)
    val snap = ctx.snapshot(desc)
    assertEquals(inst.tryPerformInternal(desc, ctx, Consts.OPTIMISTIC), McasStatus.Successful)
    assert(clue(ctx.readDirect[String](r1)) eq "x")
    assert(ctx.readDirect(r2) eq null)
    assertEquals(inst.tryPerformInternal(snap, ctx, Consts.OPTIMISTIC), McasStatus.FailedVal)
    assert(ctx.readDirect(r1) eq "x")
    assert(ctx.readDirect(r2) eq null)
  }

  test("EMCAS should clean up finalized descriptors") {
    val r1 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("y", this.rigInstance)
    val ctx = inst.currentContext()
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val desc = ctx.addCasWithVersion(ctx.start(), r1, "x", "a", version = v11)
    val snap = ctx.snapshot(desc)
    assert(ctx.tryPerformOk(ctx.addCasWithVersion(desc, r2, "y", "b", version = v21)))
    val newVer = ctx.start().validTs
    assertEquals(newVer, desc.validTs + Version.Incr)
    assert(ctx.readDirect(r1) eq "a")
    assert(ctx.readDirect(r2) eq "b")
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, newVer)
    assert(v12 > v11)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, newVer)
    assert(v22 > v21)
    assert(inst.spinUntilCleanup(r1) eq "a")
    assert(inst.spinUntilCleanup(r2) eq "b")
    assert(r1.unsafeGetV() eq "a")
    assert(r2.unsafeGetV() eq "b")
    assertEquals(ctx.readVersion(r1), v12)
    assertEquals(ctx.readVersion(r2), v22)

    val desc2 = snap
    assert(!ctx.tryPerformOk(ctx.addCasWithVersion(desc2, r2, "b", "z", version = v22))) // this will fail
    assertEquals(ctx.start().validTs, newVer)
    assert(ctx.readDirect(r1) eq "a")
    assert(ctx.readDirect(r2) eq "b")
    assertEquals(ctx.readVersion(r1), v12)
    assertEquals(ctx.readVersion(r2), v22)
    assert(inst.spinUntilCleanup(r1) eq "a")
    assert(inst.spinUntilCleanup(r2) eq "b")
    assertEquals(ctx.readVersion(r1), v12)
    assertEquals(ctx.readVersion(r2), v22)
    assert(r1.unsafeGetV() eq "a")
    assert(r2.unsafeGetV() eq "b")
  }

  test("EMCAS should handle versions correctly on cleanup (after success)") {
    val r1 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("y", this.rigInstance)
    val ctx = inst.currentContext()
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.addCasWithVersion(ctx.start(), r1, "x", "a", version = v11)
    val desc = ctx.addCasWithVersion(d0, r2, "y", "b", version = v21)
    assertEquals(ctx.tryPerform(desc), McasStatus.Successful)
    assert(ctx.readDirect(r1) eq "a")
    assert(ctx.readDirect(r2) eq "b")
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, desc.validTs + Version.Incr)
    assert(v12 > v11)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, desc.validTs + Version.Incr)
    assert(v22 > v21)
    // no GC here (probably)
    // now we run another op:
    val desc1 = ctx.addCasWithVersion(ctx.start(), r1, "a", "aa", version = v12)
    val desc2 = ctx.addCasWithVersion(desc1, r2, "b", "bb", version = v22)
    assertEquals(ctx.tryPerform(desc2), McasStatus.Successful)
    assert(ctx.readDirect(r1) eq "aa")
    assert(ctx.readDirect(r2) eq "bb")
    val v13 = ctx.readVersion(r1)
    assertEquals(v13, v12 + Version.Incr)
    val v23 = ctx.readVersion(r2)
    assertEquals(v23, v22 + Version.Incr)
    // cleanup:
    assert(inst.spinUntilCleanup(r1) eq "aa")
    assert(inst.spinUntilCleanup(r2) eq "bb")
    assertEquals(ctx.readVersion(r1), v13)
    assertEquals(ctx.readVersion(r2), v23)
  }

  test("EMCAS should handle versions correctly on cleanup (after failure)") {
    val r1 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("y", this.rigInstance)
    val ctx = inst.currentContext()
    val v11 = ctx.readVersion(r1)
    val v21 = ctx.readVersion(r2)
    val d0 = ctx.addCasWithVersion(ctx.start(), r1, "x", "a", version = v11)
    val desc = ctx.addCasWithVersion(d0, r2, "y", "b", version = v21)
    assertEquals(ctx.tryPerform(desc), McasStatus.Successful)
    assert(ctx.readDirect(r1) eq "a")
    assert(ctx.readDirect(r2) eq "b")
    val v12 = ctx.readVersion(r1)
    assertEquals(v12, desc.validTs + Version.Incr)
    assert(v12 > v11)
    val v22 = ctx.readVersion(r2)
    assertEquals(v22, desc.validTs + Version.Incr)
    assert(v22 > v21)
    // no GC here (probably)
    // now we run another op, which will fail:
    val ts0 = ctx.start().validTs
    val desc1 = ctx.addCasWithVersion(ctx.start(), r1, "a", "aa", version = v12)
    val desc2 = ctx.addCasWithVersion(desc1, r2, "x", "bb", version = v22)
    assertEquals(ctx.tryPerform(desc2), McasStatus.FailedVal)
    // commitTs didn't change, since we failed:
    assertEquals(ctx.start().validTs, ts0)
    assert(ctx.readDirect(r1) eq "a")
    assert(ctx.readDirect(r2) eq "b")
    val v13 = ctx.readVersion(r1)
    assertEquals(v13, v12)
    val v23 = ctx.readVersion(r2)
    assertEquals(v23, v22)
    // cleanup:
    assert(inst.spinUntilCleanup(r1) eq "a")
    assert(inst.spinUntilCleanup(r2) eq "b")
    assertEquals(ctx.readVersion(r1), v13)
    assertEquals(ctx.readVersion(r2), v23)
  }

  test("EMCAS should not clean up an object referenced from another thread") {
    val ref = MemoryLocation.unsafeUnpadded[String]("s", this.rigInstance)
    val ctx = inst.currentContextInternal()
    val hDesc = ctx.addCasFromInitial(ctx.start(), ref, "s", "x")
    var mark: AnyRef = null
    locally {
      val res = inst.tryPerformDebug(desc = hDesc, ctx = ctx, optimism = Consts.OPTIMISTIC)
      // TODO: if *right now* the GC clears the mark, the assertion below will fail
      mark = ref.unsafeGetMarkerV().get()
      assert(mark ne null)
      assertEquals(clue(res), McasStatus.Successful)
    }
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var ok = false
    val t = new Thread(() => {
      val mark = ref.unsafeGetMarkerV().get()
      assert(mark ne null)
      latch1.countDown()
      latch2.await()
      Reference.reachabilityFence(mark)
      ok = true
    })
    t.start()
    latch1.await()
    mark = null
    System.gc()
    assert(ref.unsafeGetMarkerV().get() ne null)
    latch2.countDown()
    t.join()
    while (ref.unsafeGetMarkerV().get() ne null) {
      System.gc()
      Thread.sleep(1L)
    }
  }

  test("EMCAS should clean up finalized descriptors if the original thread releases them") {
    val r1 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("y", this.rigInstance)
    var ok = false
    val t = new Thread(() => {
      val ctx = inst.currentContext()
      ok = ctx
        .builder()
        .casRef(r1, "x", "a")
        .casRef(r2,"y", "b")
        .tryPerformOk()
    })
    @tailrec
    def checkCleanup(ref: MemoryLocation[String], old: String, exp: String): Boolean = {
      inst.spinUntilCleanup(ref) match {
        case s if s == old =>
          // CAS not started yet, retry
          Thread.sleep(ThreadLocalRandom.current().nextLong(32L))
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
    Thread.`yield`()
    c1.start()
    c2.start()
    t.join()
    c1.join()
    c2.join()
    assert(ok)
    assert(ok1)
    assert(ok2)
  }

  test("EMCAS op should be finalizable even if a thread dies mid-op".tag(SLOW)) {
    threadDeathTest(runGcBetween = false, finishWithAnotherOp = true)
    threadDeathTest(runGcBetween = false, finishWithAnotherOp = false)
    threadDeathTest(runGcBetween = true, finishWithAnotherOp = true)
    threadDeathTest(runGcBetween = true, finishWithAnotherOp = false)
  }

  def threadDeathTest(runGcBetween: Boolean, finishWithAnotherOp: Boolean): Unit = {
    if (runGcBetween) {
      this.assumeNotOpenJ9()
    }
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var weakMark: WeakReference[AnyRef] = null
    val t1 = new Thread(() => {
      val ctx = inst.currentContext()
      val hDesc = ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), r1, "x", "a"), r2, "y", "b")
      val desc = new EmcasDescriptor(hDesc, instRo = false)
      val it = desc.getWordIterator()
      val d0 = it.next().asInstanceOf[EmcasWordDesc[String]]
      val mark = new McasMarker
      assert(d0.address eq r1)
      r1.unsafeSetV(d0.castToData)
      assert(r1.unsafeCasMarkerV(null, new WeakReference(mark)))
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
        Thread.sleep(1L)
      }
    }

    val ctx = inst.currentContext()
    if (finishWithAnotherOp) {
      // run another op; this should
      // finalize the previous one:
      val succ = ctx
        .builder()
        .casRef(r1, "x", "x2")
        .casRef(r2, "y", "y2")
        .tryPerformOk()
      assert(!succ)
    }
    // else: only run readValue; this should
    // also finalize the previous op:

    val read1 = ctx.readDirect(r1)
    assert(read1 eq "a")
    assert(ctx.readDirect(r2) eq "b")
    inst.spinUntilCleanup(r1)
    inst.spinUntilCleanup(r2)
    assert(clue(r1.unsafeGetV()) eq "a")
    assert(clue(r2.unsafeGetV()) eq "b")
    assert(weakMark.get() eq null)
  }

  test("ThreadContext should be collected by the JVM GC if a thread terminates") {
    inst.currentContext()
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    @volatile var error: Throwable = null
    val t = new Thread(() => {
      try {
        inst.currentContext()
        // now the thread exits, but the
        // thread context already exists
        latch1.countDown()
        latch2.await()
      } catch {
        case ex: Throwable =>
         error = ex
         throw ex
      }
    })
    t.start()
    latch1.await()
    latch2.countDown()
    t.join()
    assert(!t.isAlive())
    assert(error eq null, s"error: ${error}")
    while (inst.threadContextExists(t.getId())) {
      System.gc()
      Thread.sleep(1L)
    }
    // now the `ThreadContext` have been collected by the JVM GC
  }

  test("Emcas.isCurrentContext") {
    val impl = inst
    val ctx = impl.currentContext()
    assert(impl.isCurrentContext(ctx))
    var same = true
    val t = new Thread({ () =>
      same = impl.isCurrentContext(ctx)
    })
    t.start()
    t.join()
    assert(!same)
  }

  test("Emcas.isCurrentContext should not call currentContext") {
    val impl = inst
    val ctx = impl.currentContext()
    var ok = false
    val t = new Thread({ () =>
      ok = (!impl.isCurrentContext(ctx)) && (!impl.threadContextExists(Thread.currentThread().getId()))
    })
    t.start()
    t.join()
    assert(ok)
  }

  test("EMCAS should not simply replace  active descriptors (mark should be handled)".tag(SLOW)) {
    val r1 = MemoryLocation.unsafeWithId[String]("x")(0L)
    val r2 = MemoryLocation.unsafeWithId[String]("y")(1L)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    var ok0 = false
    val t1 = new Thread(() => {
      val ctx = inst.currentContext()
      val hDesc = ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), r1, "x", "a"), r2, "y", "b")
      val desc = new EmcasDescriptor(hDesc, instRo = false)
      val it = desc.getWordIterator()
      val d0 = it.next().asInstanceOf[EmcasWordDesc[String]]
      assert(d0.address eq r1)
      assert(d0.address.unsafeCasV(d0.ov, d0.castToData))
      val mark = new McasMarker
      assert(d0.address.unsafeCasMarkerV(null, new WeakReference(mark)))
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
      val ctx = inst.currentContext()
      val b = {
        ctx.builder().casRef(r2, "b", "y").tryCasRef(r1, "a", "x") match {
          case None =>
            // expected, retry:
            ctx.builder().casRef(r2, "b", "y").casRef(r1, "a", "x")
          case Some(x) =>
            fail(s"unexpected: ${x}")
        }
      }
      assert(b.tryPerformOk())
      // wait for descriptors to be collected:
      assertEquals(clue(inst.spinUntilCleanup[String](r2)), "y")
      // but this one shouldn't be collected, as the other thread holds the mark of `d0`:
      assert(inst.spinUntilCleanup(r1, max = 0x2000L) eq null)
      assert(r1.unsafeGetMarkerV().get() ne null)
      ok = true
    })
    t2.start()
    t2.join()
    assert(ok)
    latch2.countDown()
    t1.join()
    assert(ok0)

    // t1 released the mark, now it should be replaced:
    assertEquals(clue(inst.spinUntilCleanup[String](r1)), "x")
  }

  test("EMCAS read should help the other operation") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(42L)
    val ctx = inst.currentContext()
    val hOther = ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), r1, "r1", "x"), r2, "r2", "y")
    val other = new EmcasDescriptor(hOther, instRo = false)
    val d0 = other.getWordIterator().next().asInstanceOf[EmcasWordDesc[String]]
    assert(d0.address eq r1)
    r1.unsafeSetV(d0.castToData)
    val mark = new McasMarker
    assert(r1.unsafeCasMarkerV(null, new WeakReference(mark)))
    val res = ctx.readDirect(r1)
    assertEquals(res, "x")
    assertEquals(ctx.readDirect(r1), "x")
    assertEquals(ctx.readDirect(r2), "y")
    assert(EmcasStatusFunctions.isSuccessful(other.getStatusV()))
    // we hold a strong ref, since we're pretending we're another op
    Reference.reachabilityFence(mark)
  }

  test("EMCAS read should roll back the other op if necessary") {
    val r1 = MemoryLocation.unsafeWithId("r1")(0L)
    val r2 = MemoryLocation.unsafeWithId("r2")(99L)
    val ctx = inst.currentContext()
    val hOther = ctx.addCasFromInitial(ctx.addCasFromInitial(ctx.start(), r1, "r1", "x"), r2, "zzz", "y")
    val other = new EmcasDescriptor(hOther, instRo = false)
    val d0 = other.getWordIterator().next().asInstanceOf[EmcasWordDesc[String]]
    assert(d0.address eq r1)
    r1.unsafeSetV(d0.castToData)
    val mark = new McasMarker
    assert(r1.unsafeCasMarkerV(null, new WeakReference(mark)))
    val res = ctx.readDirect(r1)
    assertEquals(res, "r1")
    assertEquals(ctx.readDirect(r1), "r1")
    assertEquals(ctx.readDirect(r2), "r2")
    assert(other.getStatusV() == McasStatus.FailedVal)
    // we hold a strong ref, since we're pretending we're another op
    Reference.reachabilityFence(mark)
  }

  test("ThreadContexts should be thread-local") {
    val N = 10000
    val tc1 = new ConcurrentLinkedQueue[EmcasThreadContext]
    val tc2 = new ConcurrentLinkedQueue[EmcasThreadContext]
    val tsk = (tc: ConcurrentLinkedQueue[EmcasThreadContext]) => {
      tc.offer(inst.currentContextInternal())
      Thread.sleep(10L)
      for (_ <- 1 to N) {
        tc.offer(inst.currentContextInternal())
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
    final class TrickyThread(ref: VolatileObjectRef[EmcasThreadContext]) extends Thread {
      final override def getId(): Long = 42L
      final override def run(): Unit = {
        ref.elem = inst.currentContextInternal()
      }
    }
    val r1 = VolatileObjectRef.create(nullOf[EmcasThreadContext])
    val t1 = new TrickyThread(r1)
    t1.start()
    t1.join()
    val r2 = VolatileObjectRef.create(nullOf[EmcasThreadContext])
    val t2 = new TrickyThread(r2)
    t2.start()
    t2.join()
    assert(r1.elem ne r2.elem)
    assertEquals(r1.elem.tid, r2.elem.tid)
  }

  test("ThreadContext cleanup".tag(SLOW)) {
    this.assumeNotOpenJ9()
    val K = 100
    val N = 25 * K
    val task: Runnable = () => {
      val ctx = inst.currentContext()
      ctx.random.nextInt()
      ()
    }
    for (i <- 1 to N) {
      val t = new Thread(task)
      t.start()
      t.join()
      if ((i % K) == 0) {
        System.gc()
      }
    }
    assert(inst.threadContextCount().toDouble <= (0.75 * N.toDouble))
  }

  test("Descriptors should be sorted") {
    val r1 = MemoryLocation.unsafeWithId("r1")(1L)
    val r2 = MemoryLocation.unsafeWithId("r2")(2L)
    val r3 = MemoryLocation.unsafeWithId("r3")(3L)
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "r1", "A")
    val d2 = ctx.addCasFromInitial(d1, r3, "r3", "C")
    val d3 = ctx.addCasFromInitial(d2, r2, "r2", "B")
    val d = new EmcasDescriptor(d3, instRo = false)
    val it = d.getWordIterator()
    assertSameInstance(it.next().address, r1)
    assertSameInstance(it.next().address, r2)
    assertSameInstance(it.next().address, r3)
    assert(!it.hasNext())
  }

  test("Descriptor toString") {
    for (r1 <- List(MemoryLocation.unsafeUnpadded("r1", this.rigInstance), MemoryLocation.unsafePadded("r1", this.rigInstance))) {
      val ctx = inst.currentContext()
      val d0 = ctx.start()
      val d1 = ctx.addCasFromInitial(d0, r1, "r1", "A")
      val ed = new EmcasDescriptor(d1, instRo = false)
      val wd = ed.getWordIterator().next()
      assert(wd.toString().startsWith("EmcasWordDesc("))
      assert(ctx.tryPerformOk(d1))
      (r1.unsafeGetV() : Any) match {
        case wd: EmcasWordDesc[_] =>
          assert(wd.toString().startsWith("EmcasWordDesc("))
        case x =>
          fail(s"unexpected contents: ${x}")
      }
    }
  }

  private[this] final def runInNewThread[A](block: => A): A = {
    var err: Throwable = null
    var result: A = nullOf[A]
    val t = new Thread(() => {
      result = block
    })
    t.setUncaughtExceptionHandler((_, ex) => {
      err = ex
      ex.printStackTrace()
    })
    t.start()
    t.join()
    if (err ne null) {
      throw err
    }
    result
  }

  test("Version mismatch, but expected value is the same") {
    val ref = MemoryLocation.unsafeUnpadded("A", this.rigInstance)
    val ctx = inst.currentContext()
    // T1:
    val d0 = ctx.start()
    val Some((ov, d1)) = ctx.readMaybeFromLog(ref, d0, canExtend = true) : @unchecked
    assertSameInstance(ov, "A")
    assertEquals(d1.getOrElseNull(ref).version, Version.Start)
    // T2:
    runInNewThread {
      val ctx = inst.currentContext()
      val oldVer = ctx.start().validTs
      assert(ctx.tryPerformSingleCas(ref, "A", "B"))
      assert(ctx.readVersion(ref) > oldVer)
      assertSameInstance(ctx.readDirect(ref), "B")
    }
    // T3:
    runInNewThread {
      val ctx = inst.currentContext()
      val oldVer = ctx.start().validTs
      assert(ctx.tryPerformSingleCas(ref, "B", "A"))
      assert(ctx.readVersion(ref) > oldVer)
      assertSameInstance(ctx.readDirect(ref), "A")
    }
    // GC, cleanup:
    assertSameInstance(inst.spinUntilCleanup(ref), "A")
    val ver = ctx.readVersion(ref)
    assert(VersionFunctions.isValid(ver))
    assert(ver > Version.Start)
    assertSameInstance(ref.unsafeGetV(), "A")
    // T1 continues:
    val d2 = d1.overwrite(d1.getOrElseNull(ref).withNv("C"))
    val result = ctx.tryPerform(d2)
    assertEquals(result, McasStatus.FailedVal)
    val ver2 = ctx.readVersion(ref)
    // version mustn't decrease:
    assert(ver2 >= ver, s"${ver2} < ${ver}")
    assertSameInstance(inst.spinUntilCleanup(ref), "A")
    val ver3 = ctx.readVersion(ref)
    assert(ver3 >= ver2, s"${ver3} < ${ver2}")
  }

  test("There should be no global version-CAS") {
    val r1 = MemoryLocation.unsafeUnpadded[String]("foo", this.rigInstance)
    val r2 = MemoryLocation.unsafeUnpadded[String]("bar", this.rigInstance)
    val ctx = inst.currentContext()
    val d0 = ctx.start()
    val d1 = ctx.addCasFromInitial(d0, r1, "foo", "bar")
    val d2 = ctx.addCasFromInitial(d1, r2, "bar", "foo")
    val d = new EmcasDescriptor(d2, instRo = false)
    val lb = List.newBuilder[MemoryLocation[?]]
    val it = d.getWordIterator()
    while (it.hasNext()) {
      lb += it.next().address
    }
    val lst: List[MemoryLocation[?]] = lb.result()
    assertEquals(lst.length, 2)
    assert((lst(0) eq r1) || (lst(0) eq r2))
    assert((lst(1) eq r1) || (lst(1) eq r2))
  }

  test("Version.Incr should be 1") {
    // EMCAS assumes we're incrementing by 1,
    // so we have a test to remember this:
    assertEquals(Version.Incr, 1L)
  }

  test("Threads should have different `ThreadLocalRefIdGen`s") {
    val ids = new ConcurrentSkipListSet[Long]
    val rig1 = inst.currentContext().refIdGen
    ids.add(rig1.nextId())
    var rig2: RefIdGen = null
    val t = new Thread(() => {
      val r = inst.currentContext().refIdGen
      ids.add(r.nextId())
      ids.add(r.nextId())
      ids.add(r.nextId())
      rig2 = r
    })
    t.start()
    ids.add(rig1.nextId())
    ids.add(rig1.nextId())
    t.join()
    rig2 match {
      case null =>
        fail("missing RIG")
      case rig2 =>
        assert(rig1 ne rig2)
    }
    assertEquals(ids.size(), 6)
  }

  test("EmcasDescriptor#instRo") {
    val ref = MemoryLocation.unsafeWithId[String]("foo")(1L)
    val ref2 = MemoryLocation.unsafeWithId[String]("foo")(2L)
    val ctx = inst.currentContext()
    val ed1 = new EmcasDescriptor(ctx.start().add(LogEntry(ref, "foo", "bar", Version.Start)), instRo = false)
    assert(!ed1.instRo)
    val arr1 = ed1.getWordsO()
    assertSameInstance(arr1(0).asInstanceOf[EmcasWordDesc[?]].parent, ed1)

    val entry2 = LogEntry(ref, "foo", "foo", Version.Start)
    val entry3 = LogEntry(ref2, "foo", "bar", Version.Start)
    val ed2 = new EmcasDescriptor(ctx.start().add(entry2).add(entry3), instRo = false)
    assert(!ed2.instRo)
    val arr2 = ed2.getWordsO()
    assertSameInstance(arr2(0), entry2)
    assertSameInstance(arr2(1).asInstanceOf[EmcasWordDesc[?]].parent, ed2)

    val ed3 = new EmcasDescriptor(ctx.start().add(entry2).add(entry3), instRo = true)
    assert(ed3.instRo)
    val arr3 = ed3.getWordsO()
    assertSameInstance(arr3(0).asInstanceOf[EmcasWordDesc[?]].parent, ed3)
    assertSameInstance(arr3(1).asInstanceOf[EmcasWordDesc[?]].parent, ed3)
  }

  test("EmcasDescriptor#fallback") {
    val ref = MemoryLocation.unsafeUnpadded[String]("foo", this.rigInstance)
    val ctx = inst.currentContext()
    val ed1 = new EmcasDescriptor(ctx.start().add(LogEntry(ref, "foo", "bar", Version.Start)), instRo = false)
    assert(!ed1.instRo)
    assertEquals(ed1.cmpxchgStatus(McasStatus.Active, EmcasStatus.CycleDetected), McasStatus.Active)
    assertSameInstance(ed1.getWordsO()(0).asInstanceOf[EmcasWordDesc[?]].parent, ed1)
    ed1.wasFinalized(EmcasStatus.CycleDetected)
    assertSameInstance(ed1.getWordsO(), null)
    val ed2 = ed1.fallback
    assertSameInstance(ed2.getWordsO()(0).asInstanceOf[EmcasWordDesc[?]].parent, ed2)
    assert(ed2.instRo)
    assertSameInstance(ed1.fallback, ed2)
    assert(Either.catchOnly[AssertionError](ed2.fallback).isLeft)
    assert(ed1.getWordsO() ne ed2.getWordsO())
  }

  test("EmcasDescriptor#fallback call before wasFinalized call") {
    val ref = MemoryLocation.unsafeUnpadded[String]("foo", this.rigInstance)
    val ctx = inst.currentContext()
    val ed1 = new EmcasDescriptor(ctx.start().add(LogEntry(ref, "foo", "bar", Version.Start)), instRo = false)
    assert(!ed1.instRo)
    assertEquals(ed1.cmpxchgStatus(McasStatus.Active, EmcasStatus.CycleDetected), McasStatus.Active)
    val ed2 = ed1.fallback
    assertSameInstance(ed1.getWordsO()(0).asInstanceOf[EmcasWordDesc[?]].parent, ed1)
    assertSameInstance(ed2.getWordsO()(0).asInstanceOf[EmcasWordDesc[?]].parent, ed2)
    ed1.wasFinalized(EmcasStatus.CycleDetected)
    assertSameInstance(ed1.getWordsO(), null)
    assert(ed2.instRo)
    assertSameInstance(ed1.fallback, ed2)
    assert(Either.catchOnly[AssertionError](ed2.fallback).isLeft)
    assert(ed1.getWordsO() ne ed2.getWordsO())
  }

  test("There should be no EmcasWordDesc created for RO HWDs (the first time; in optimistic mode)") {
    val ref1 = MemoryLocation.unsafeUnpadded[String]("foo", this.rigInstance)
    val ref2 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val ctx = inst.currentContext()
    val desc = ctx
      .start()
      .add(LogEntry(ref1, "foo", "bar", Version.Start)) // RW
      .add(LogEntry(ref2, "x", "x", Version.Start)) // RO
    val ed1 = new EmcasDescriptor(desc, instRo = false)
    assert(!ed1.instRo)
    assertEquals(ed1.cmpxchgStatus(McasStatus.Active, EmcasStatus.CycleDetected), McasStatus.Active)
    val (wd1, wd2) = if (MemoryLocation.orderInstance.lt(ref1, ref2)) {
      (ed1.getWordsO()(0).asInstanceOf[WdLike[Any]], ed1.getWordsO()(1).asInstanceOf[WdLike[Any]])
    } else {
      (ed1.getWordsO()(1).asInstanceOf[WdLike[Any]], ed1.getWordsO()(0).asInstanceOf[WdLike[Any]])
    }
    assertSameInstance(wd1.asInstanceOf[EmcasWordDesc[?]].parent, ed1)
    assert(wd2.isInstanceOf[LogEntry[?]])

    ed1.wasFinalized(EmcasStatus.CycleDetected)
    assertSameInstance(ed1.getWordsO(), null)
    val ed2 = ed1.fallback
    val (wd21, wd22) = if (MemoryLocation.orderInstance.lt(ref1, ref2)) {
      (ed2.getWordsO()(0).asInstanceOf[WdLike[Any]], ed2.getWordsO()(1).asInstanceOf[WdLike[Any]])
    } else {
      (ed2.getWordsO()(1).asInstanceOf[WdLike[Any]], ed2.getWordsO()(0).asInstanceOf[WdLike[Any]])
    }
    assertSameInstance(wd21.asInstanceOf[EmcasWordDesc[?]].parent, ed2)
    assertSameInstance(wd22.asInstanceOf[EmcasWordDesc[?]].parent, ed2)
    assert(wd21 ne wd1)
    assert(ed2.instRo)
    assertSameInstance(ed1.fallback, ed2)
    assert(Either.catchOnly[AssertionError](ed2.fallback).isLeft)
  }

  test("In pessimistic mode, even RO HWDs must have WDs created") {
    val ref1 = MemoryLocation.unsafeUnpadded[String]("foo", this.rigInstance)
    val ref2 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val ctx = inst.currentContext()
    val desc = ctx
      .start()
      .add(LogEntry(ref1, "foo", "bar", Version.Start)) // RW
      .add(LogEntry(ref2, "x", "x", Version.Start)) // RO
    val ed1 = new EmcasDescriptor(desc, instRo = true)
    assert(ed1.instRo)
    val (wd1, wd2) = if (MemoryLocation.orderInstance.lt(ref1, ref2)) {
      (ed1.getWordsO()(0).asInstanceOf[WdLike[Any]], ed1.getWordsO()(1).asInstanceOf[WdLike[Any]])
    } else {
      (ed1.getWordsO()(1).asInstanceOf[WdLike[Any]], ed1.getWordsO()(0).asInstanceOf[WdLike[Any]])
    }
    assertSameInstance(wd1.asInstanceOf[EmcasWordDesc[?]].parent, ed1)
    assertSameInstance(wd2.asInstanceOf[EmcasWordDesc[?]].parent, ed1)
  }

  test("AbstractDescriptor#readOnly is false, but in fact it is read-only") {
    val ref1 = MemoryLocation.unsafeUnpadded[String]("a", this.rigInstance)
    val ref2 = MemoryLocation.unsafeUnpadded[String]("x", this.rigInstance)
    val ctx = inst.currentContext()
    val dRw = ctx
      .start()
      .add(LogEntry(ref1, "a", "b", Version.Start)) // RW
      .add(LogEntry(ref2, "x", "x", Version.Start)) // RO
    val snapRw = ctx.snapshot(dRw)
    val dRo = dRw.overwrite(LogEntry(ref1, "a", "a", Version.Start)) // becomes RO
    val snapRo = snapRw.overwrite(LogEntry(ref1, "a", "a", Version.Start)) // becomes RO
    assert(!dRo.readOnly)
    assert(!snapRo.readOnly)
    val ed1 = new EmcasDescriptor(dRo, instRo = false)
    assertEquals(ed1.getWordsP(), null)
    assert(ctx.tryPerformOk(dRo, optimism = Consts.OPTIMISTIC))
    val ed2 = new EmcasDescriptor(dRo, instRo = true)
    assertEquals(ed2.getWordsP(), null)
    assert(ctx.tryPerformOk(dRo, optimism = Consts.PESSIMISTIC))
    val ed3 = new EmcasDescriptor(snapRo, instRo = false)
    assertEquals(ed3.getWordsP(), null)
    assert(ctx.tryPerformOk(snapRo, optimism = Consts.OPTIMISTIC))
    val ed4 = new EmcasDescriptor(snapRo, instRo = true)
    assertEquals(ed4.getWordsP(), null)
    assert(ctx.tryPerformOk(snapRo, optimism = Consts.PESSIMISTIC))
  }
}
