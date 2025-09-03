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
package bench
package rxn

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import core.{ Rxn, Ref }
import util.{ McasImplStateBase, RandomState }

/**
 * A variant of `dev.tauri.choam.mcas.bench.ResourceAllocationMcas`,
 * implemented with `Rxn`.
 */
@Fork(2)
class ResourceAllocationRxn {

  import ResourceAllocationRxn.{ ResAllocSt, ThreadSt }

  @Benchmark
  def bench(s: ResAllocSt, t: ThreadSt, rnd: RandomState): Unit = {
    val n = t.allocSize
    val rss = t.selectResources(s.rss, rnd)

    @tailrec
    def read(i: Int, arr: Array[String], acc: Rxn[Unit]): Rxn[Unit] = {
      if (i >= n) {
        acc
      } else {
        val r = Rxn.unsafe.directRead(rss(i))
        read(i + 1, arr, acc *> r.flatMap { s =>
          Rxn.unsafe.delay { arr(i) = s }
        })
      }
    }

    @tailrec
    def write(i: Int, ovs: Array[String], acc: Rxn[Unit]): Rxn[Unit] = {
      if (i >= n) {
        acc
      } else {
        val r = Rxn.unsafe.cas(rss(i), ovs(i), ovs((i + 1) % n))
        write(i + 1, ovs, (acc *> r))
      }
    }

    val r = read(0, t.ovs, Rxn.unit)
    val w = write(0, t.ovs, Rxn.unit)
    (r *> w).unsafePerform(t.mcasImpl)

    Blackhole.consumeCPU(t.tokens)
  }
}

object ResourceAllocationRxn {

  private[this] final val nRes = 96

  @State(Scope.Benchmark)
  class ResAllocSt extends McasImplStateBase {

    private[this] val initialValues =
      Vector.fill(nRes)(scala.util.Random.nextString(10))

    final val rss: Array[Ref[String]] =
      initialValues.map(Ref.unsafe(_, Ref.AllocationStrategy.Padded, this.mcasImpl.currentContext().refIdGen)).toArray

    @TearDown
    final def checkResults(): Unit = {
      val ctx = this.mcasImpl.currentContext()
      val currentValues = rss.map(ref => ctx.readDirect(ref.loc)).toVector
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
  class ThreadSt extends McasImplStateBase {

    final val tokens = 128L

    private[this] var selectedRss: Array[Ref[String]] = null

    final var ovs: Array[String] = null

    @Param(Array("2", "4", "6"))
    @nowarn("cat=unused-privates")
    private[this] var dAllocSize: Int = 0

    final def allocSize: Int =
      dAllocSize

    @Setup
    final def setupSelRes(): Unit = {
      selectedRss = new Array[Ref[String]](allocSize)
      ovs = new Array[String](allocSize)
      java.lang.invoke.VarHandle.releaseFence()
    }

    /** Select `allocSize` refs randomly */
    final def selectResources(rss: Array[Ref[String]], rs: RandomState): Array[Ref[String]] = {
      val bucketSize = nRes / allocSize
      assert((allocSize * bucketSize) == nRes)

      @tailrec
      def next(off: Int, dest: Int): Unit = {
        if (dest >= allocSize) {
          ()
        } else {
          val rnd = java.lang.Math.abs(rs.nextInt() % bucketSize)
          selectedRss(dest) = rss(off + rnd)
          next(off + bucketSize, dest + 1)
        }
      }

      next(0, 0)
      selectedRss
    }
  }
}
