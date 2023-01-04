/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package util

import scala.concurrent.stm._

import StmQueue._

class StmQueue[A] private[this] (sentinel: Node[A], els: Iterable[A]) {

  def this(els: Iterable[A]) =
    this(Node(nullOf[A], Ref(End[A]())), els)

  def this() =
    this(Iterable.empty)

  private[this] val head: Ref[Node[A]] =
    Ref(sentinel)

  private[this] val tail: Ref[Node[A]] =
    Ref(sentinel)

  atomic { implicit txn =>
    els.foreach(enqueue)
  }

  def enqueue(a: A)(implicit mt: MaybeTxn): Unit = atomic { implicit txn =>
    val node = Node(a, Ref[Elem[A]](End[A]()))
    tail.get.next.get match {
      case End() =>
        tail.get.next.set(node)
        tail.set(node)
      case Node(_, _) =>
        impossible("lagging tail")
    }
  }

  def tryDequeue()(implicit mt: MaybeTxn): Option[A] = atomic { implicit txn =>
    head.get.next.get match {
      case n @ Node(a, _) =>
        head.set(n.copy(data = nullOf[A]))
        Some(a)
      case End() =>
        None
    }
  }

  def unsafeToList()(implicit mt: MaybeTxn): List[A] = atomic { implicit txn =>
    @tailrec
    def go(e: Elem[A], acc: List[A]): List[A] = e match {
      case Node(null, next) =>
        // sentinel
        go(next.get, acc)
      case Node(a, next) =>
        go(next.get, a :: acc)
      case End() =>
        acc
    }

    go(head.get, Nil).reverse
  }
}

object StmQueue {
  private sealed trait Elem[A]
  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]
  private final case class End[A]() extends Elem[A]
}
