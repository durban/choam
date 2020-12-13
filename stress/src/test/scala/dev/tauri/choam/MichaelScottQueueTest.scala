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

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.infra.results.LLL_Result

import kcas.KCAS

@KCASParams("Michael-Scott queue enq/deq should be atomic")
@Outcomes(Array(
  new Outcome(id = Array("Some(z), Some(x), List(y)", "Some(z), None, List(x, y)"), expect = ACCEPTABLE, desc = "enq1 first; deq1 first"),
  new Outcome(id = Array("Some(x), Some(z), List(y)", "None, Some(z), List(x, y)"), expect = ACCEPTABLE, desc = "enq1 first; deq2 first"),
  new Outcome(id = Array("Some(z), Some(y), List(x)", "Some(z), None, List(y, x)"), expect = ACCEPTABLE, desc = "enq2 first; deq1 first"),
  new Outcome(id = Array("Some(y), Some(z), List(x)", "None, Some(z), List(y, x)"), expect = ACCEPTABLE, desc = "enq2 first; deq2 first")
))
abstract class MichaelScottQueueTest(impl: KCAS) {

  private[this] val queue =
    new MichaelScottQueue[String](List("z"))

  private[this] val enqueue =
    queue.enqueue

  private[this] val tryDeque =
    queue.tryDeque

  @Actor
  def enq1(): Unit = {
    enqueue.unsafePerform("x")
  }

  @Actor
  def enq2(): Unit = {
    enqueue.unsafePerform("y")
  }

  @Actor
  def deq1(r: LLL_Result): Unit = {
    r.r1 = tryDeque.unsafeRun
  }

  @Actor
  def deq2(r: LLL_Result): Unit = {
    r.r2 = tryDeque.unsafeRun
  }

  @Arbiter
  def arbiter(r: LLL_Result): Unit = {
    r.r3 = queue.unsafeToList
  }
}
