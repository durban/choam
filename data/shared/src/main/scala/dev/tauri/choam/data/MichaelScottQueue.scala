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

import MichaelScottQueue._

// TODO: This is really bad for the GC; consider using
// TODO: optimizations like in `juc.ConcurrentLinkedQueue`.
private[choam] final class MichaelScottQueue[A] private[this] (
  sentinel: Node[A],
  padded: Boolean,
) extends Queue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafePadded(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.unsafePadded(sentinel)

  private def this(padded: Boolean) =
    this(Node(nullOf[A], Ref.unsafePadded(End[A]())), padded = padded)

  override val tryDeque: Axn[Option[A]] = {
    head.modifyWith { node =>
      node.next.get.flatMapF { next =>
        next match {
          case n @ Node(a, _) =>
            Rxn.pure((n.copy(data = nullOf[A]), Some(a)))
          case End() =>
            Rxn.pure((node, None))
        }
      }
    }
  }

  override val enqueue: Rxn[A, Unit] = Rxn.computed { (a: A) =>
    // TODO: This is cheating: we're using
    // TODO: `computed` as `delay` (`newNode`
    // TODO: has a side-effect).
    findAndEnqueue(newNode(a))
  }

  private[this] def newNode(a: A): Node[A] = {
    val newRef: Ref[Elem[A]] = if (this.padded) {
      Ref.unsafePadded(End[A]())
    } else {
      Ref.unsafeUnpadded(End[A]())
    }
    Node(a, newRef)
  }

  private[this] def findAndEnqueue(node: Node[A]): Axn[Unit] = {
    def go(n: Node[A], originalTail: Node[A]): Axn[Unit] = {
      n.next.get.flatMapF {
        case End() =>
          // found true tail; will update, and try to adjust the tail ref:
          n.next.set.provide(node).postCommit(tail.unsafeCas(originalTail, node).?.void)
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and continue:
          go(n = nv, originalTail = originalTail)
      }
    }
    tail.get.flatMapF { t => go(t, t) }
  }
}

private[choam] object MichaelScottQueue {

  private sealed trait Elem[A]
  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]
  private final case class End[A]() extends Elem[A]

  def apply[A]: Axn[MichaelScottQueue[A]] =
    padded[A]

  def padded[A]: Axn[MichaelScottQueue[A]] =
    applyInternal(padded = true)

  def unpadded[A]: Axn[MichaelScottQueue[A]] =
    applyInternal(padded = false)

  private[this] def applyInternal[A](padded: Boolean): Axn[MichaelScottQueue[A]] =
    Rxn.unsafe.delay { _ => new MichaelScottQueue(padded = padded) }
}
