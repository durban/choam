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

import scala.annotation.tailrec

import org.openjdk.jcstress.annotations.{ Ref => _, _ }
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.ILL_Result
import java.util.concurrent.ThreadLocalRandom

@JCStressTest
@State
@Description("EMCASCleanup1Test")
@Outcomes(Array(
  new Outcome(id = Array("1, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), null"), expect = ACCEPTABLE, desc = "(1) has desc, but no value yet"),
  new Outcome(id = Array("1, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), b"), expect = ACCEPTABLE, desc = "(2) has desc and value too"),
  new Outcome(id = Array("1, null, null"), expect = FORBIDDEN, desc = "(X) no desc, no value"),
  new Outcome(id = Array("1, null, b"), expect = ACCEPTABLE_INTERESTING, desc = "(3) no desc, but has value")
))
class EMCASCleanup1Test {

  private[this] val ref =
    Ref.mk("a")

  @Actor
  def write(r: ILL_Result): Unit = {
    val ok = EMCAS
      .start()
      .withCAS(this.ref, "a", "b")
      .tryPerform()
    r.r1 = if (ok) 1 else -1
  }

  @Actor
  @tailrec
  final def read(r: ILL_Result): Unit = {
    this.maybeGc()
    (this.ref.unsafeTryRead() : Any) match {
      case s: String if s eq "a" =>
        // no CAS yet, retry:
        read(r)
      case wd: EMCASWeakData[_] =>
        // observing the descriptor:
        r.r2 = wd.get()
        r.r3 = wd.getValueVolatile()
      case x =>
        // we're late:
        r.r2 = x
        r.r3 = x
    }
  }

  /**
   * Runs the JVM GC with a low probability, so
   * that we can occasionally observe the case
   * when descriptors were collected.
   */
  private final def maybeGc(): Unit = {
    if ((ThreadLocalRandom.current().nextInt() % 4096) == 0) {
      System.gc()
    }
  }

  @Arbiter
  def arbiter(r: ILL_Result): Unit = {
    // WordDescriptor is not serializable:
    r.r2 match {
      case wd: EMCAS.WordDescriptor[_] =>
        r.r2 = wd.toString()
      case _ =>
        ()
    }
  }
}
