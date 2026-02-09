/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ZZL_Result

import core.Ref

@JCStressTest
@State
@Description("k-CAS should be atomic (with versions, after another op)")
@Outcomes(Array(
  new Outcome(id = Array("true, false, x"), expect = ACCEPTABLE_INTERESTING, desc = "writer1 succeeded"),
  new Outcome(id = Array("false, true, y"), expect = ACCEPTABLE_INTERESTING, desc = "writer2 succeeded"),
))
class McasTestVer extends StressTestBase {

  private[this] val refs: Array[MemoryLocation[String]] = Array(
    Ref.unsafe("ov", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("ov", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("-", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("-", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("-", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("-", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
    Ref.unsafe("-", AllocationStrategy.Padded, impl.currentContext().refIdGen).loc,
  )

  this.init()

  @tailrec
  private[this] def init(): Unit = {
    val s = this.writeInternal("-", "ov", startIdx = 2)
    if (s != McasStatus.Successful) {
      if (s == McasStatus.FailedVal) {
        throw new AssertionError
      } else  {
        // failed due to commit-ts changing, retry:
        init()
      }
    }
  }

  private[this] def write(ov: String, nv: String): Boolean = {
    writeInternal(ov, nv) == McasStatus.Successful
  }

  private[this] def writeInternal(ov: String, nv: String, startIdx: Int = 0): Long = {
    val ctx = impl.currentContext()
    var d = ctx.start()
    var idx = startIdx
    while (idx < refs.length) {
      val ref = refs(idx)
      ctx.readMaybeFromLog(ref, d, canExtend = true) match {
        case Some((value, desc)) =>
          if (value eq ov) {
            d = desc.overwrite(desc.getOrElseNull(ref).withNv(nv))
          } else {
            return McasStatus.FailedVal // scalafix:ok
          }
        case None =>
          write(ov, nv) // retry
      }
      idx += 1
    }
    ctx.tryPerform(d)
  }

  @Actor
  def writer1(r: ZZL_Result): Unit = {
    r.r1 = write("ov", "x")
  }

  @Actor
  def writer2(r: ZZL_Result): Unit = {
    r.r2 = write("ov", "y")
  }

  @Arbiter
  def arbiter(r: ZZL_Result): Unit = {
    val ctx = impl.currentContext()
    val vs = refs.map(ref => ctx.readDirect(ref))
    val s = vs.toSet
    if (s.size == 1) {
      r.r3 = s.iterator.next()
    } else {
      r.r3 = s.toString
    }
  }
}
