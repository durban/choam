/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import _root_.dev.tauri.choam.bench.util.KCASImplState

/**
 * Resource allocation scenario, described in [Software transactional memory](
 * https://pdfs.semanticscholar.org/846e/87f6c8b9d8909d678b5c668cfe46cf40a348.pdf)
 * by Nir Shavit and Dan Touitou.
 */
@Fork(2)
class ResourceAllocationKCAS {

  import ResourceAllocationKCAS._

  @Benchmark
  def bench(s: RaSt, t: ThSt): Unit = {
    val n = t.allocSize
    val ctx = t.kcasCtx
    val rss = t.selectResources(s.rss)
    t.desc = ctx.start()

    @tailrec
    def read(i: Int): Unit = {
      if (i >= n) {
        ()
      } else {
        val Some((_, newDesc)) = ctx.readMaybeFromLog(rss(i), t.desc) : @unchecked
        t.desc = newDesc
        read(i + 1)
      }
    }

    @tailrec
    def prepare(i: Int): Unit = {
      if (i >= n) {
        ()
      } else {
        val nv = t.desc.getOrElseNull(rss((i + 1) % n)).ov
        val newDesc = t.desc.overwrite(t.desc.getOrElseNull(rss(i)).withNv(nv))
        t.desc = newDesc
        prepare(i + 1)
      }
    }

    @tailrec
    def go(): Unit = {
      read(0)
      prepare(0)
      if (ctx.tryPerformOk(t.desc)) ()
      else go()
    }

    go()
    Blackhole.consumeCPU(t.tokens)
  }
}

object ResourceAllocationKCAS {

  private[this] final val nRes = 60

  @State(Scope.Benchmark)
  class RaSt {

    private[this] val initialValues =
      Vector.fill(nRes)(scala.util.Random.nextString(10))

    val rss: Array[MemoryLocation[String]] =
      initialValues.map(Ref.unsafe(_).loc).toArray

    @TearDown
    def checkResults(): Unit = {
      val ctx = Mcas.DefaultMcas.currentContext()
      val currentValues = rss.map { ref =>
        ctx.readDirect(ref)
      }.toVector
      if (currentValues == initialValues) {
        throw new Exception(s"Unchanged results")
      }
      val cv = currentValues.sorted
      val iv = initialValues.sorted
      if (cv != iv) {
        throw new Exception(s"Invalid results: ${cv} != ${iv}")
      }
    }
  }

  @State(Scope.Thread)
  class ThSt extends KCASImplState {

    final val tokens = 128L

    private[this] var selectedRss: Array[MemoryLocation[String]] = _

    var desc: HalfEMCASDescriptor = _

    @Param(Array("2", "4", "6"))
    private[this] var dAllocSize: Int = _

    def allocSize: Int =
      dAllocSize

    @Setup
    def setupSelRes(): Unit = {
      selectedRss = Array.ofDim(allocSize)
    }

    /** Select `allocSize` refs randomly */
    def selectResources(rss: Array[MemoryLocation[String]]): Array[MemoryLocation[String]] = {
      val bucketSize = nRes / allocSize

      @tailrec
      def next(off: Int, dest: Int): Unit = {
        if (dest >= allocSize) {
          ()
        } else {
          val rnd = (nextInt() % bucketSize).abs
          selectedRss(dest) = rss(off + rnd)
          next(off + bucketSize, dest + 1)
        }
      }

      next(0, 0)
      selectedRss
    }
  }
}
