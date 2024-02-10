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

import org.openjdk.jcstress.annotations.{ Ref => _, Outcome => JOutcome, _ }
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.LL_Result

@JCStressTest
@State
@Description("Cycles?")
@Outcomes(Array(
  new JOutcome(id = Array("List(a, b, c, d), null"), expect = ACCEPTABLE_INTERESTING, desc = "ok"),
))
class CycleTest {

  import CycleTest.Node

  private[this] val second: Node =
    new Node("b", new Node("c", new Node("d", null)))

  private[this] val first: Node =
    new Node("a", second)

  @Actor
  def callbackStackApply(r: LL_Result): Unit = {
    var log = List.empty[String]
    var currentNode = this.first
    while (currentNode ne null) {
      val cb = currentNode.callback
      if (cb ne null) {
        log = cb :: log
        currentNode.callback = null
      }
      val nextNode = currentNode.next
      currentNode.next = null
      currentNode = nextNode
    }
    r.r1 = log.reverse
  }

  @Actor
  def callbackStackPackTail(): Unit = {
    def go(node: Node, prev: Node): Unit = {
      val next = node.next
      if (node.callback eq null) {
        prev.next = next
        if (next eq null) {
          ()
        } else {
          go(next, prev)
        }
      } else {
        if (next eq null) {
          ()
        } else {
          go(next, node)
        }
      }
    }

    val first = this.first
    val second = this.second
    if (second ne null) {
      go(second, first)
    }
  }
}

object CycleTest {

  final class Node(
    var callback: String,
    var next: Node,
  ) {
  }
}
