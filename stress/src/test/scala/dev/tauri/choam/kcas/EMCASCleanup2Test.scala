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
import org.openjdk.jcstress.infra.results.LLLLL_Result
import java.util.concurrent.ThreadLocalRandom

@JCStressTest
@State
@Description("EMCASCleanup2Test")
@Outcomes(Array(
  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), UNINITIALIZED, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), UNINITIALIZED"), expect = ACCEPTABLE, desc = "(1) read1: has desc, no value yet; read2: has desc, no value yet"),
  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), b, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), UNINITIALIZED"), expect = ACCEPTABLE, desc = "(2) read1: has desc, has value; read2: has desc, no value yet"),
  new Outcome(id = Array("true, null, UNINITIALIZED, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), UNINITIALIZED"), expect = FORBIDDEN, desc = "(X) read1: no desc, no value; read2: has desc, no value yet"),
  new Outcome(id = Array("true, null, b, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), UNINITIALIZED"), expect = ACCEPTABLE, desc = "(3) read1: no desc, has value; read2: has desc, no value yet"),

  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), UNINITIALIZED, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), y"), expect = ACCEPTABLE, desc = "(4) read1: has desc, no value yet; read2: has desc, has value"),
  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), b, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), y"), expect = ACCEPTABLE, desc = "(5) read1: has desc, has value; read2: has desc, has value"),
  new Outcome(id = Array("true, null, UNINITIALIZED, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), y"), expect = FORBIDDEN, desc = "(X) read1: no desc, no value; read2: has desc, has value"),
  new Outcome(id = Array("true, null, b, WordDescriptor\\(Ref@[a-z0-9]*, x, y\\), y"), expect = ACCEPTABLE, desc = "(6) read1: no desc, has value; read2: has desc, has value"),

  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), UNINITIALIZED, null, UNINITIALIZED"), expect = FORBIDDEN, desc = "(X) read1: has desc, no value yet; read2: no desc, no value"),
  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), b, null, UNINITIALIZED"), expect = FORBIDDEN, desc = "(X) read1: has desc, has value; read2: no desc, no value"),
  new Outcome(id = Array("true, null, UNINITIALIZED, null, UNINITIALIZED"), expect = FORBIDDEN, desc = "(X) read1: no desc, no value; read2: no desc, no value"),
  new Outcome(id = Array("true, null, b, null, UNINITIALIZED"), expect = FORBIDDEN, desc = "(X) read1: no desc, has value; read2: no desc, no value"),

  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), UNINITIALIZED, null, y"), expect = ACCEPTABLE, desc = "(7) read1: has desc, no value yet; read2: no desc, has value"),
  new Outcome(id = Array("true, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), b, null, y"), expect = ACCEPTABLE, desc = "(8) read1: has desc, has value; read2: no desc, has value"),
  new Outcome(id = Array("true, null, UNINITIALIZED, null, y"), expect = FORBIDDEN, desc = "(X) read1: no desc, no value; read2: no desc, has value"),
  new Outcome(id = Array("true, null, b, null, y"), expect = ACCEPTABLE, desc = "(9) read1: no desc, has value; read2: no desc, has value")
))
class EMCASCleanup2Test {

  private[this] val ref1 =
    Ref.mk("a")

  private[this] val ref2 =
    Ref.mk("x")

  @Actor
  def write(r: LLLLL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    r.r1 = EMCAS.tryPerform(
      EMCAS.addCas(EMCAS.addCas(EMCAS.start(ctx), this.ref1, "a", "b"), this.ref2, "x", "y")
    )
  }

  @Actor
  @tailrec
  final def read1(r: LLLLL_Result): Unit = {
    this.maybeGc()
    (this.ref1.unsafeTryRead() : Any) match {
      case s: String if s eq "a" =>
        // no CAS yet, retry:
        read1(r)
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

  @Actor
  final def read2(r: LLLLL_Result): Unit = {
    this.maybeGc()
    (this.ref2.unsafeTryRead() : Any) match {
      case s: String if s eq "x" =>
        // no CAS yet, retry:
        read2(r)
      case wd: EMCASWeakData[_] =>
        // observing the descriptor:
        r.r4 = wd.get()
        r.r5 = wd.getValueVolatile()
      case x =>
        // we're late:
        r.r4 = x
        r.r5 = x
    }
  }

  /**
   * Runs the JVM GC with a low probability, so
   * that we can occasionally observe the case
   * when descriptors were collected.
   */
  private final def maybeGc(): Unit = {
    if ((ThreadLocalRandom.current().nextInt() % 8192) == 0) {
      System.gc()
    }
  }

  @Arbiter
  def arbiter(r: LLLLL_Result): Unit = {
    // WordDescriptor is not serializable:
    r.r2 match {
      case wd: WordDescriptor[_] =>
        r.r2 = wd.toString()
      case _ =>
        ()
    }
    r.r4 match {
      case wd: WordDescriptor[_] =>
        r.r4 = wd.toString()
      case _ =>
        ()
    }
  }
}
