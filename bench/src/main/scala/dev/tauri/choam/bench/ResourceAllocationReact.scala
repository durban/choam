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
package bench

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import util.KCASImplState
import kcas.{ Ref, KCAS }

/**
 * A variant of `dev.tauri.choam.kcas.bench.ResourceAllocation`,
 * implemented with reagents.
 */
@Fork(2)
class ResourceAllocationReact {

  import ResourceAllocationReact._

  @Benchmark
  def bench(s: ResAllocSt, t: ThreadSt): Unit = {
    val n = t.allocSize
    val rss = t.selectResources(s.rss)

    @tailrec
    def read(i: Int, react: React[Unit, Array[String]]): React[Unit, Array[String]] = {
      if (i >= n) {
        react
      } else {
        val r = rss(i).invisibleRead
        read(i + 1, react.map2(r) { (arr, s) =>
          arr(i) = s
          arr
        })
      }
    }

    @tailrec
    def write(i: Int, react: React[Array[String], Unit]): React[Array[String], Unit] = {
      if (i >= n) {
        react
      } else {
        val r = React.computed[Array[String], Unit] { ovs =>
          rss(i).cas(ovs(i), ovs((i + 1) % n))
        }
        write(i + 1, (react * r).discard)
      }
    }

    val r = read(0, React.ret(t.ovs))
    val w = write(0, React.unit)
    (r >>> w).unsafeRun(t.kcasImpl)

    Blackhole.consumeCPU(t.tokens)
  }
}

object ResourceAllocationReact {

  private[this] final val nRes = 60

  @State(Scope.Benchmark)
  class ResAllocSt {

    private[this] val initialValues =
      Vector.fill(nRes)(scala.util.Random.nextString(10))

    val rss: Array[Ref[String]] =
      initialValues.map(Ref.mk).toArray

    @TearDown
    def checkResults(): Unit = {
      val currentValues = rss.map(_.invisibleRead.unsafeRun(KCAS.NaiveKCAS)).toVector
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
  class ThreadSt extends KCASImplState {

    final val tokens = 128L

    private[this] var selectedRss: Array[Ref[String]] = _

    var ovs: Array[String] = _

    @Param(Array("2", "4", "6"))
    private[this] var dAllocSize: Int = _

    def allocSize: Int =
      dAllocSize

    @Setup
    def setupSelRes(): Unit = {
      selectedRss = Array.ofDim(allocSize)
      ovs = Array.ofDim(allocSize)
      java.lang.invoke.VarHandle.releaseFence()
    }

    /** Select `allocSize` refs randomly */
    def selectResources(rss: Array[Ref[String]]): Array[Ref[String]] = {
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
