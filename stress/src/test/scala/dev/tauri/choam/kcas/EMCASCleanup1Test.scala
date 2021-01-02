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

@JCStressTest
@State
@Description("EMCASCleanup1Test")
@Outcomes(Array(
  new Outcome(id = Array("1, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), ACTIVE"), expect = ACCEPTABLE, desc = "(1) has desc, active op"),
  new Outcome(id = Array("1, WordDescriptor\\(Ref@[a-z0-9]*, a, b\\), SUCCESSFUL"), expect = ACCEPTABLE, desc = "(2) has desc, finalized op"),
  new Outcome(id = Array("1, b, -"), expect = ACCEPTABLE_INTERESTING, desc = "(3) final value, desc was cleaned up")
))
class EMCASCleanup1Test {

  private[this] val ref =
    Ref.mk("a")

  @Actor
  final def write(r: ILL_Result): Unit = {
    val ctx = EMCAS.currentContext()
    val ok = EMCAS.tryPerform(EMCAS.addCas(EMCAS.start(ctx), this.ref, "a", "b"), ctx)
    r.r1 = if (ok) 1 else -1
  }

  @Actor
  @tailrec
  final def read(r: ILL_Result): Unit = {
    (this.ref.unsafeTryRead() : Any) match {
      case s: String if s eq "a" =>
        // no CAS yet, retry:
        read(r)
      case wd: WordDescriptor[_] =>
        // observing the descriptor:
        r.r2 = wd
        r.r3 = wd.parent.getStatus()
      case x =>
        // was cleaned up, observing final value:
        r.r2 = x
        r.r3 = "-"
    }
  }

  @Arbiter
  final def arbiter(r: ILL_Result): Unit = {
    // WordDescriptor is not serializable:
    r.r2 match {
      case wd: WordDescriptor[_] =>
        r.r2 = wd.toString()
      case _ =>
        ()
    }
  }
}
