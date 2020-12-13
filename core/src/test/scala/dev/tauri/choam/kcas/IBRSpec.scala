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

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalactic.TypeCheckedTripleEquals

final class IBRSpec
  extends AnyFlatSpec
  with Matchers
  with TypeCheckedTripleEquals {

  import IBRSpec.{ Descriptor, GC }

  "IBR" should "work" in {
    val gc = new GC
    val tc = gc.threadContext()
    tc.startOp()
    val ref = try {
      val d1 = tc.alloc()
      val ref = new AtomicReference(d1)
      val d2 = tc.alloc()
      assert(tc.cas(ref, d1, d2))
      assert(!tc.cas(ref, d1, d2))
      assert(tc.readAcquire(ref) eq d2)
      tc.retire(d1)
      tc.retire(d2)
      ref
    } finally tc.endOp()
    tc.fullGc()
    assert(ref.get().freed == 1)
  }

  it should "not free an object referenced from another thread" in {
    val gc = new GC
    val ref = new AtomicReference[Descriptor](null)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    @volatile var error = false
    // bg thread:
    val t = new Thread(() => {
      val tc = gc.threadContext()
      tc.startOp()
      try {
        val d1 = tc.readAcquire(ref)
        latch1.countDown()
        latch2.await()
        // we're still using `d1` ...
        if (d1.freed > 0) {
          error = true
        }
      } finally tc.endOp()
    })
    // main thread:
    val tc = gc.threadContext()
    tc.startOp()
    val d1 = try {
      val d1 = tc.alloc()
      assert(tc.cas(ref, null, d1))
      t.start()
      latch1.await()
      // now `t` is still using `d1`, so it mustn't be freed, even if we retire it:
      tc.write(ref, null)
      tc.retire(d1)
      d1
    } finally tc.endOp()
    tc.fullGc()
    // make `t` end its op:
    latch2.countDown()
    t.join()
    assert(!error)
    // now it can be freed:
    tc.fullGc()
    assert(d1.freed == 1)
  }

  it should "reuse objects form the freelist" in {
    val gc = new GC
    val tc = gc.threadContext()
    tc.startOp()
    val d = try {
      val d = tc.alloc()
      tc.retire(d)
      d
    } finally tc.endOp()
    tc.fullGc()
    assert(d.freed == 1)
    tc.startOp()
    try {
      val d2 = tc.alloc()
      assert(d2 eq d)
      tc.retire(d2)
    } finally tc.endOp()
    tc.fullGc()
    assert(d.freed == 2)
  }

  "The epoch" should "be incremented after a few allocations" in {
    val gc = new GC
    val tc = gc.threadContext()
    val startEpoch = gc.epochNumber
    val descs = for (_ <- 1 until IBR.epochFreq) yield {
      tc.startOp()
      try {
        tc.alloc()
      } finally tc.endOp()
    }
    // the next allocation triggers the new epoch:
    tc.startOp()
    try {
      tc.alloc()
    } finally tc.endOp()
    val newEpoch = gc.epochNumber
    assert(newEpoch === (startEpoch + 1))
    for (desc <- descs) {
      tc.startOp()
      try {
        tc.retire(desc)
      } finally tc.endOp()
    }
    for (desc <- descs) {
      assert(desc.getBirthEpochOpaque() == startEpoch)
      assert(desc.getRetireEpochOpaque() === newEpoch)
    }
  }

  "A reclamation" should "run after a few retirements" in {
    val gc = new GC
    val tc = gc.threadContext()
    val ref = new AtomicReference[Descriptor](null)
    val seen = new java.util.IdentityHashMap[Descriptor, Unit]
    @tailrec
    def go(cnt: Int): Int = {
      val done = tc.op {
        val d = tc.alloc()
        if (seen.containsKey(d)) {
          // a descriptor was freed and reused
          assert(d.freed == 1)
          // we're done
          true
        } else {
          seen.put(d, ())
          val prev = tc.readAcquire(ref)
          assert(tc.cas(ref, prev, d))
          if (prev ne null) tc.retire(prev)
          // continue:
          false
        }
      }
      if (done) cnt
      else go(cnt + 1)
    }
    val cnt = go(0)
    assert(cnt === IBR.emptyFreq)
  }

  "ThreadContext" should "be collected by the JVM GC if a thread terminates" in {
    val gc = new GC
    val tc = gc.threadContext()
    val firstEpoch = gc.epochNumber
    val ref = new AtomicReference[Descriptor](null)
    val d = tc.op {
      val d = tc.alloc()
      tc.write(ref, d)
      d
    }
    @volatile var error: Throwable = null
    val t = new Thread(() => {
      try {
        val tc = gc.threadContext()
        tc.startOp()
        val d = tc.readAcquire(ref)
        assert(d.freed === 0)
        // now the thread exits while still "using" the
        // descriptor, because it doesn't call `endOp`
        assert(tc.snapshotReservation.lower === firstEpoch)
        assert(tc.snapshotReservation.upper === firstEpoch)
        d.foobar()
      } catch {
        case ex: Throwable =>
         error = ex
         throw ex
      }
    })
    t.start()
    t.join()
    assert(!t.isAlive())
    assert(error eq null, s"error: ${error}")
    while (gc.snapshotReservations(t.getId()).get() ne null) {
      System.gc()
    }
    // now the `ThreadContext` have been collected by the JVM GC
    tc.op {
      tc.cas(ref, d, null)
      tc.retire(d)
    }
    assert(d.getRetireEpochOpaque() === firstEpoch)
    tc.fullGc() // this should collect `d`
    assert(d.freed === 1)
    assert(gc.snapshotReservations.get(t.getId()).isEmpty)
  }

  it should "not block reclamation of newer blocks if a thread deadlocks" in {
    val gc = new GC
    val tc = gc.threadContext()
    val firstEpoch = gc.epochNumber
    val ref = new AtomicReference[Descriptor](null)
    val d = tc.op {
      val d = tc.alloc()
      tc.write(ref, d)
      d
    }
    @volatile var error: Throwable = null
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val t = new Thread(() => {
      try {
        val tc = gc.threadContext()
        tc.startOp()
        val d = tc.readAcquire(ref)
        assert(d.freed === 0)
        // now the thread deadlocks while still "using" the
        // descriptor, because it doesn't call `endOp`
        assert(tc.snapshotReservation.lower === firstEpoch)
        assert(tc.snapshotReservation.upper === firstEpoch)
        latch1.countDown()
        latch2.await() // blocks forever
      } catch {
        case ex: Throwable =>
         error = ex
         throw ex
      }
    })
    t.start()
    latch1.await()
    assert(t.isAlive())
    assert(error eq null, s"error: ${error}")
    assert(gc.snapshotReservations(t.getId()).get() ne null)
    tc.op {
      tc.cas(ref, d, null)
      tc.retire(d) // `d` will never be collected, because `t` protects it
    }
    assert(d.getRetireEpochOpaque() === firstEpoch)
    tc.fullGc() // this will not collect `d`
    assert(d.freed === 0)
    // but newet objects should be reclaimed:
    val d2 = tc.op {
      val d2 = tc.alloc()
      assert(d2.getBirthEpochOpaque() > firstEpoch)
      tc.cas(ref, null, d2)
      d2
    }
    assert(d.freed === 0)
    assert(d2.freed === 0)
    tc.op {
      tc.cas(ref, d2, null)
      tc.retire(d2)
    }
    assert(d2.getRetireEpochOpaque() === gc.epochNumber)
    tc.fullGc() // this should collect `d2` (but not `d`)
    assert(d.freed === 0)
    assert(d2.freed === 1)
  }
}

final object IBRSpec {

  final case class Descriptor(dummy: String)
    extends DebugManaged[TC, Descriptor] {

    final def foobar(): Unit =
      this.checkAccess()
  }

  final class TC(global: GC)
    extends IBR.ThreadContext[TC, Descriptor](global)

  final class GC
    extends IBR[TC, Descriptor](zeroEpoch = 0L) {

    protected[kcas] override def allocateNew(): Descriptor =
      Descriptor("dummy")

    protected[kcas] override def dynamicTest[A](a: A): Boolean =
      a.isInstanceOf[Descriptor]

    protected[kcas] override def newThreadContext(): TC =
      new TC(this)
  }
}
