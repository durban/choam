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
package data

import GcHostileMsQueue._

/**
 * Unoptimized Michael-Scott queue (for benchmarks)
 *
 * It is GC-hostile, because it allocates a lot of
 * objects, including `Ref`s, which are only used once
 * (see `GcBench`).
 */
private[choam] final class GcHostileMsQueue[A] private[this] (sentinel: Node[A], els: Iterable[A])
  extends Queue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafe(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.unsafe(sentinel)

  private def this(els: Iterable[A]) =
    this(Node(nullOf[A], Ref.unsafe(End[A]())), els)

  override val tryDeque: Axn[Option[A]] = {
    for {
      node <- head.unsafeInvisibleRead
      next <- node.next.unsafeInvisibleRead
      res <- next match {
        case n @ Node(a, _) =>
          // No need to also validate `node.next`, since
          // it is not the last node (thus it won't change).
          head.unsafeCas(node, n.copy(data = nullOf[A])) >>> Rxn.ret(Some(a))
        case End() =>
          head.unsafeCas(node, node) >>> node.next.unsafeCas(next, next) >>> Rxn.ret(None)
      }
    } yield res
  }

  override val enqueue: Rxn[A, Unit] = Rxn.computed { (a: A) =>
    findAndEnqueue(Node(a, Ref.unsafe(End[A]())))
  }

  private[this] def findAndEnqueue(node: Node[A]): Axn[Unit] = {
    Rxn.unsafe.delayComputed(tail.unsafeInvisibleRead.flatMapF { (n: Node[A]) =>
      n.next.unsafeInvisibleRead.flatMapF {
        case e @ End() =>
          // found true tail; will CAS, and try to adjust the tail ref:
          Rxn.ret(n.next.unsafeCas(e, node).postCommit(tail.unsafeCas(n, node).?.void))
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and will retry:
          tail.unsafeCas(n, nv).?.as(Rxn.unsafe.retry)
      }
    })
  }

  els.foreach { a =>
    enqueue.unsafePerform(a, mcas.MCAS.ThreadConfinedMCAS)
  }
}

private[choam] object GcHostileMsQueue {

  private sealed trait Elem[A]
  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]
  private final case class End[A]() extends Elem[A]

  def fromList[A](as: List[A]): Axn[GcHostileMsQueue[A]] =
    Rxn.unsafe.delay { _ => new GcHostileMsQueue(as) }
}