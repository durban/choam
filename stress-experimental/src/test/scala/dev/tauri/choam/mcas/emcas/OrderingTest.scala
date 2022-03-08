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
package emcas

import java.util.concurrent.atomic.AtomicInteger

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LL_Result

/**
 * This test shows a problem with a previously existing
 * assertion in EMCAS. If we read `null`, we logically
 * "know", that `flag` must be `1` (i.e., the descriptor
 * is finalized), but we might not actually "see" it
 * with a volatile read. The reason for this is that
 * while reading the value written by a volatile write
 * (or CAS) ensures a happens-before relationship, the
 * converse is not true: reading the previous value
 * does NOT create a "happens-after" relationship.
 *
 * Experiments show, that if instead of the volatile
 * read, we have another CAS, that CAS will always
 * fail if we read `null`. TODO: Explain this (based
 * on the memory model).
 */
@JCStressTest
@State
@Description("Emcas status/array")
@Outcomes(Array(
  new Outcome(id = Array("abcd, 0"), expect = ACCEPTABLE, desc = ""),
  new Outcome(id = Array("abcd, 1"), expect = ACCEPTABLE, desc = ""),
  new Outcome(id = Array("null, 0"), expect = ACCEPTABLE_INTERESTING, desc = ""),
  new Outcome(id = Array("null, 1"), expect = ACCEPTABLE, desc = ""),
))
class OrderingTest {

  private[this] var str: String =
    "abcd"

  private[this] val flag: AtomicInteger =
    new AtomicInteger(0)

  @Actor
  def finalizer(): Unit = {
    assert(this.flag.compareAndSet(0, 1))
    this.str = null
  }

  @Actor
  def reader(r: LL_Result): Unit = {
    val s: String = this.str
    val f: Int = this.flag.get()
    r.r1 = s
    r.r2 = f
  }
}
