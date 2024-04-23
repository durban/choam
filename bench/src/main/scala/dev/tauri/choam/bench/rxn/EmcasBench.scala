/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import internal.mcas.Mcas
import internal.mcas.Consts
import EmcasBench._

@Fork(3)
@Threads(2)
class EmcasBench {

  @Benchmark
  def emcas8_readWrite(s: ThreadSt): Unit = {
    s.doReadWrite()
  }

  @Benchmark
  def emcas8_writeOnly(s: ThreadSt): Unit = {
    s.doWriteOnly()
  }
}

object EmcasBench {

  abstract class BaseSt {

    private[this] final val N = 8

    private[this] val refs: Array[Ref[String]] = Array.tabulate(N) { idx =>
      val id = idx.toLong << 58 // make sure the HAMT is flat (i.e., a hash table)
      dev.tauri.choam.refs.unsafeNewRefP1("a")(id)
    }

    protected def ctx: Mcas.ThreadContext

    final def doWriteOnly(): Unit = {
      assert(goWriteOnly(this.ctx))
    }

    final def doReadWrite(): Unit = {
      assert(goReadWrite(this.ctx))
    }

    @tailrec
    private[this] final def goWriteOnly(ctx: Mcas.ThreadContext): Boolean = {
      // all refs change value atomically "a" -> "b"
      // then do the same "b" -> "a"
      // then repeat
      val d0 = ctx.start()
      val refs = this.refs
      val r1 = refs(0)
      val hwd1 = ctx.readIntoHwd(r1.loc)
      val ov = hwd1.ov
      val nv = if (ov eq "a") "b" else "a"
      val oldVer = hwd1.oldVersion
      var d = d0.add(hwd1.withNv(nv))
      var idx = 1
      while (idx < N) {
        d = ctx.addCasWithVersion(d, refs(idx).loc, ov = ov, nv = nv, version = oldVer)
        idx += 1
      }
      if (ctx.tryPerformOk(d, Consts.PESSIMISTIC)) true
      else goWriteOnly(ctx)
    }

    @tailrec
    private[this] final def goReadWrite(ctx: Mcas.ThreadContext): Boolean = {
      // even indices are only read (but note: in case of cycles, their version changes!)
      // odd indices do the same as in `goWriteOnly`
      val d0 = ctx.start()
      val refs = this.refs
      val r0 = refs(0).loc
      val r1 = refs(1).loc
      val hwd0 = ctx.readIntoHwd(r0)
      val hwd1 = ctx.readIntoHwd(r1)
      val oldVerEven = hwd0.oldVersion
      val ovOdd = hwd1.ov
      val nv = if (ovOdd eq "a") "b" else "a"
      val oldVerOdd = hwd1.oldVersion
      var d = d0.add(hwd0).add(hwd1.withNv(nv))
      var idx = 2
      while (idx < N) {
        if ((idx % 2) == 0) {
          // even:
          d = ctx.addCasWithVersion(d, refs(idx).loc, ov = "a", nv = "a", version = oldVerEven)
        } else {
          // odd:
          d = ctx.addCasWithVersion(d, refs(idx).loc, ov = ovOdd, nv = nv, version = oldVerOdd)
        }
        idx += 1
      }
      if (ctx.tryPerformOk(d, Consts.OPTIMISTIC)) true
      else goReadWrite(ctx)
    }
  }

  @State(Scope.Thread)
  class ThreadSt extends BaseSt {

    private[this] var _ctx: Mcas.ThreadContext =
      null

    final def ctx: Mcas.ThreadContext =
      this._ctx

    @Setup
    def setup(): Unit = {
      this._ctx = Mcas.Emcas.currentContext()
    }
  }
}
