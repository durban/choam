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

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LLLLLL_Result

// @JCStressTest
@State
@Description("Cycles?")
@Outcomes(Array(
  new JOutcome(id = Array(".*"), expect = ACCEPTABLE_INTERESTING, desc = "whatever"),
))
class CycleTest {

  import CycleTest.Node

  private[this] var a: Node =
    null

  private[this] var b: Node =
    null

  @Actor
  def write(): Unit = {
    this.a = new Node("a", new Node("x", null))
    this.b = new Node("b", null)
    this.a.callback = null
  }

  @Actor
  def read(r: LLLLLL_Result): Unit = {
    val a = this.a
    r.r1 = a
    val b = this.b
    r.r5 = b
    if (a ne null) {
      val aCb = a.callback
      val aNext = a.next
      r.r2 = aCb
      r.r3 = aNext
      if (aNext ne null) {
        r.r4 = aNext.callback
      }
    }
    if (b ne null) {
      val bCb = b.callback
      r.r6 = bCb
    }
  }

  @Arbiter
  def arbiter(r: LLLLLL_Result): Unit = {
    r.r1 match {
      case n: Node =>
        r.r1 = n.toString
      case _ =>
        ()
    }
    r.r3 match {
      case n: Node =>
        r.r3 = n.toString
      case _ =>
        ()
    }
    r.r5 match {
      case n: Node =>
        r.r5 = n.toString
      case _ =>
        ()
    }
  }
}

object CycleTest {

  final class Node(
    var callback: String,
    var next: Node,
  ) {

    final override def toString: String = {
      s"Node(${callback}, ${next})"
    }
  }
}
