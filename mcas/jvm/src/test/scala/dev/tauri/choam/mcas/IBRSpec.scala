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
import java.lang.ref.Reference

final class IBRSpec
  extends BaseSpecA {

  test("IBR should not free an object referenced from another thread") {
    val ref = MemoryLocation.unsafe[String]("s")
    val ctx = EMCAS.currentContext()
    val hDesc = ctx.addCas(ctx.start(), ref, "s", "x")
    val desc = {
      val desc = EMCASDescriptor.prepare(hDesc)
      assert(EMCAS.MCAS(desc = desc, ctx = ctx, replace = EMCAS.replacePeriodForEMCAS))
      desc
    }
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    val t = new Thread(() => {
      val mark = desc.wordIterator().next().tryHold()
      latch1.countDown()
      latch2.await()
      Reference.reachabilityFence(mark)
    })
    t.start()
    latch1.await()
    val wd = desc.wordIterator().next()
    System.gc()
    assert(wd.tryHold() ne null)
    latch2.countDown()
    t.join()
    while (wd.tryHold() ne null) {
      System.gc()
    }
  }

  test("ThreadContext should be collected by the JVM GC if a thread terminates") {
    EMCAS.currentContext()
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)
    @volatile var error: Throwable = null
    val t = new Thread(() => {
      try {
        EMCAS.currentContext()
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
    while (EMCAS.global.threadContexts().exists(_.tid == t.getId())) {
      System.gc()
    }
    // now the `ThreadContext` have been collected by the JVM GC
  }
}
