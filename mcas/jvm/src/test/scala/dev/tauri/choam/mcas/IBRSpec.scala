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

import java.util.concurrent.CountDownLatch

import mcas.MemoryLocation

final class IBRSpec
  extends BaseSpecA {

  test("IBR should not free an object referenced from another thread") {
    val ref = MemoryLocation.unsafe[String]("s")
    val ctx = EMCAS.currentContext()
    val hDesc = ctx.addCas(ctx.start(), ref, "s", "x")
    val desc = ctx.op {
      val desc = EMCASDescriptor.prepare(hDesc, ctx)
      assert(EMCAS.MCAS(desc = desc, ctx = ctx, replace = EMCAS.replacePeriodForEMCAS))
      desc
    }
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val t = new Thread(() => {
      val ctx = EMCAS.currentContext()
      ctx.startOp()
      try {
        latch1.countDown()
        latch2.await()
      } finally ctx.endOp()
    })
    t.start()
    latch1.await()
    assert(ctx.isInUseByOther(desc.wordIterator().next()))
    latch2.countDown()
    t.join()
    assert(!ctx.isInUseByOther(desc.wordIterator().next()))
  }

  test("The epoch should be incremented after a few allocations") {
    val ctx = EMCAS.currentContext()
    ctx.forceNextEpoch()
    ctx.resetCounter(0)
    val startEpoch = EMCAS.global.epochNumber
    val descs = for (_ <- 1 until IBR.epochFreq) yield {
      ctx.op {
        WordDescriptor.prepare(
          HalfWordDescriptor(null, "", ""),
          null,
          ctx,
        )
      }
    }
    // the next allocation triggers the new epoch:
    ctx.op {
      WordDescriptor.prepare(
        HalfWordDescriptor(null, "", ""),
        null,
        ctx,
      )
    }
    val newEpoch = EMCAS.global.epochNumber
    assertEquals(newEpoch, (startEpoch + 1))
    for (desc <- descs) {
      assertEquals(desc.getMinEpochAcquire(), startEpoch)
    }
  }

  test("ThreadContext should be collected by the JVM GC if a thread terminates") {
    val ctx = EMCAS.currentContext()
    ctx.forceNextEpoch()
    ctx.resetCounter(0)
    val firstEpoch = EMCAS.global.epochNumber
    val d = ctx.op {
      WordDescriptor.prepare(
        HalfWordDescriptor(null, "", ""),
        null,
        ctx,
      )
    }
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    @volatile var error: Throwable = null
    val t = new Thread(() => {
      try {
        val ctx = EMCAS.currentContext()
        ctx.startOp()
        assertEquals(EMCAS.global.epochNumber, firstEpoch)
        // now the thread exits while still "using" the
        // descriptor, because it doesn't call `endOp`
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
    assert(ctx.isInUseByOther(d))
    latch2.countDown()
    t.join()
    assert(!t.isAlive())
    assert(error eq null, s"error: ${error}")
    while (EMCAS.global.snapshotReservations(t.getId()).get() ne null) {
      System.gc()
    }
    // now the `ThreadContext` have been collected by the JVM GC
    assert(!ctx.isInUseByOther(d))
  }

  test("ThreadContext should not block reclamation of newer blocks if a thread deadlocks") {
    val ctx = EMCAS.currentContext()
    ctx.forceNextEpoch()
    ctx.resetCounter(0)
    val firstEpoch = EMCAS.global.epochNumber
    val d = ctx.op {
      WordDescriptor.prepare(
        HalfWordDescriptor(null, "", ""),
        null,
        ctx,
      )
    }
    @volatile var error: Throwable = null
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val t = new Thread(() => {
      try {
        val ctx = EMCAS.currentContext()
        ctx.startOp()
        // now the thread deadlocks while still "using" the
        // descriptor, because it doesn't call `endOp`
        assertEquals(ctx.snapshotReservation.lower, firstEpoch)
        assertEquals(ctx.snapshotReservation.upper, firstEpoch)
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
    assert(EMCAS.global.snapshotReservations(t.getId()).get() ne null)
    assert(ctx.isInUseByOther(d))
    // but newer objects should be able to free:
    ctx.forceNextEpoch()
    val d2 = ctx.op {
      val d2 = WordDescriptor.prepare(
        HalfWordDescriptor(null, "", ""),
        null,
        ctx,
      )
      assert(d2.getMinEpochAcquire() > firstEpoch)
      d2
    }
    assert(ctx.isInUseByOther(d))
    assert(!ctx.isInUseByOther(d2))
  }
}
