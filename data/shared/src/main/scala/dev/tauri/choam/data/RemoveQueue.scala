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

import RemoveQueue._

/**
 * Like `MichaelScottQueue`, but also has support
 * for interior node deletion (`remove`; based on
 * `java.util.concurrent.ConcurrentLinkedQueue`).
 */
private[choam] final class RemoveQueue[A] private[this] (sentinel: Node[A])
  extends Queue.WithRemove[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafe(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.unsafe(sentinel)

  def this() =
    this(Node(nullOf[Ref[A]], Ref.unsafe(End[A]())))

  override val tryDeque: Axn[Option[A]] = {
    head.modifyWith { node =>
      skipTombs(from = node.next).flatMap {
        case None =>
          // empty queue:
          Rxn.ret((node, None))
        case Some((a, n)) =>
          // deque first node (and drop tombs before it):
          Rxn.ret((n.copy(data = nullOf[Ref[A]]), Some(a)))
      }
    }
  }

  private[this] def skipTombs(from: Ref[Elem[A]]): Axn[Option[(A, Node[A])]] = {
    from.get.flatMapF {
      case n @ Node(dataRef, nextRef) =>
        dataRef.get.flatMapF { a =>
          if (isTombstone(a)) {
            skipTombs(nextRef)
          } else {
            Rxn.pure(Some((a, n)))
          }
        }
      case End() =>
        Rxn.pure(None)
    }
  }

  override val enqueue: Rxn[A, Unit] = Rxn.computed { (a: A) =>
    Ref[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref(a).flatMap { dataRef =>
        findAndEnqueue(Node(dataRef, nextRef))
      }
    }
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

  /**
   * Removes a single instance of the input
   *
   * Note: an item is only removed if it is identical to
   * (i.e., the same object as) the input. That is, items
   * are compared by reference equality.
   */
  override val remove: Rxn[A, Boolean] = Rxn.computed { (a: A) =>
    head.get.flatMapF { h =>
      findAndTomb(a, h.next)
    }
  }

  private[this] def findAndTomb(item: A, from: Ref[Elem[A]]): Axn[Boolean] = {
    from.get.flatMapF {
      case Node(dataRef, nextRef) =>
        dataRef.get.flatMapF { a =>
          if (equ(a, item)) {
            // found it
            dataRef.set.provide(tombstone[A]).as(true)
          } else {
            // continue search:
            findAndTomb(item, nextRef)
          }
        }
      case End() =>
        Rxn.pure(false)
    }
  }
}

private[choam] object RemoveQueue {

  def apply[A]: Axn[RemoveQueue[A]] =
    Rxn.unsafe.delay { _ => new RemoveQueue }

  def fromList[A](as: List[A]): Axn[RemoveQueue[A]] = {
    Rxn.unsafe.context { ctx =>
      val q = new RemoveQueue[A]
      as.foreach { a =>
        q.enqueue.unsafePerformInternal(a, ctx = ctx)
      }
      q
    }
  }

  private sealed trait Elem[A]

  /**
   * Sentinel node (head and tail): `data` is `null` (not a `Ref`).
   * Deleted (tombstone) node: `data` is a `Ref` which contains `null`.
   */
  private final case class Node[A](data: Ref[A], next: Ref[Elem[A]]) extends Elem[A]

  private final case class End[A]() extends Elem[A]

  private[this] final object Tombstone {
    final def as[A]: A =
      this.asInstanceOf[A]
  }

  private def tombstone[A]: A =
    Tombstone.as[A]

  private def isTombstone[A](a: A): Boolean =
    equ(a, tombstone[A])
}
