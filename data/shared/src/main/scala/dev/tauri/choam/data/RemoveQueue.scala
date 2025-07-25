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
package data

import core.{ Rxn, Ref }
import internal.mcas.RefIdGen
import RemoveQueue.{ Elem, Node, End, dequeued, isDequeued, isRemoved }

/**
 * Internal API, for (Gen)WaitList to use.
 *
 * Like `MsQueue`, but also has support for interior node deletion
 * (`remove`), based on the public domain JSR-166 ConcurrentLinkedQueue
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
 *
 * TODO: also unlink removed nodes (instead of just tombing them).
 */
private[choam] final class RemoveQueue[A] private[this] (sentinel: Node[A], initRig: RefIdGen)
  extends Queue.UnsealedQueue[A] {

  // TODO: do the optimization with ticketRead (like in `MsQueue`)

  private[this] val head: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)
  private[this] val tail: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)

  def this(initRig: RefIdGen) =
    this(Node(nullOf[Ref[A]], Ref.unsafeUnpadded(End[A](), initRig)), initRig = initRig)

  final override val poll: Rxn[Option[A]] = {
    head.get.flatMap { node =>
      skipRemoved(from = node.next).flatMap {
        case None =>
          // empty queue:
          Rxn.none
        case Some((a, n)) =>
          // deque first node (and drop tombs before it):
          head.set(n.copy(data = nullOf[Ref[A]])).as(Some(a))
      }
    }
  }

  private[this] final def skipRemoved(from: Ref[Elem[A]]): Rxn[Option[(A, Node[A])]] = {
    from.get.flatMap {
      case n @ Node(dataRef, nextRef) =>
        dataRef.get.flatMap { a =>
          if (isRemoved(a)) {
            skipRemoved(nextRef)
          } else if (isDequeued(a)) {
            impossible("poll found an already dequeued node")
          } else {
            dataRef.set(dequeued[A]).as(Some((a, n)))
          }
        }
      case End() =>
        Rxn.none
    }
  }

  val isEmpty: Rxn[Boolean] = {
    def go(from: Ref[Elem[A]]): Rxn[Boolean] = {
      from.get.flatMap {
        case Node(dataRef, nextRef) =>
          dataRef.get.flatMap { a =>
            if (isRemoved(a)) {
              go(nextRef)
            } else if (isDequeued(a)) {
              impossible("isEmpty found an already dequeued node")
            } else {
              Rxn.false_
            }
          }
        case End() =>
          Rxn.true_
      }
    }

    head.get.flatMap { node => go(node.next) }
  }

  final override def offer(a: A): Rxn[Boolean] =
    this.add(a).as(true)

  final override def add(a: A): Rxn[Unit] = {
    Ref.unpadded[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref.unpadded(a).flatMap { dataRef =>
        findAndEnqueue(Node(dataRef, nextRef))
      }
    }
  }

  final def enqueueWithRemover(a: A): Rxn[Rxn[Boolean]] = {
    Ref.unpadded[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref.unpadded(a).flatMap { dataRef =>
        val newNode = Node(dataRef, nextRef)
        findAndEnqueue(newNode).as(newNode.remover)
      }
    }
  }

  // TODO: we could allow tail to lag by a constant
  private[this] final def findAndEnqueue(node: Node[A]): Rxn[Unit] = {
    def go(n: Node[A]): Rxn[Unit] = {
      n.next.get.flatMap {
        case End() =>
          // found true tail; will update, and adjust the tail ref:
          n.next.set(node) *> tail.set(node)
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and continue:
          go(n = nv)
      }
    }
    tail.get.flatMap(go)
  }
}

private[choam] object RemoveQueue {

  def apply[A]: Rxn[RemoveQueue[A]] =
    Rxn.unsafe.delayContext { ctx => new RemoveQueue(ctx.refIdGen) }

  private sealed trait Elem[A]

  /**
   * Sentinel node (head and tail): `data` is `null` (not a `Ref`).
   * Deleted (tombstone) node: `data` is a `Ref` which contains `Tombstone`.
   */
  private final case class Node[A](data: Ref[A], next: Ref[Elem[A]])
    extends Elem[A] {

    final def remover: Rxn[Boolean] = {
      this.data.modify { ov =>
        if (isDequeued(ov) || isRemoved(ov)) {
          // we can't cancel it, because either
          // it was already dequeued, or someone
          // else already cancelled it
          (ov, false)
        } else {
          (removed[A], true)
        }
      }
    }
  }

  private final case class End[A]() extends Elem[A]

  private final object End {

    private[this] final val _end: End[Any] =
      new End[Any]()

    final def apply[A](): End[A] =
      _end.asInstanceOf[End[A]]
  }

  // Note: it's important, that user code
  // can never access a sentinel, as we
  // need to be able to reliably distinguish
  // user data from a sentinel (this is why
  // we can't simply use `null`).

  private[this] val _dequeued: AnyRef =
    new AnyRef

  private[this] val _removed: AnyRef =
    new AnyRef

  @inline
  private final def dequeued[A]: A =
    _dequeued.asInstanceOf[A]

  @inline
  private final def isDequeued[A](a: A): Boolean =
    equ(a, _dequeued)

  @inline
  private[this] final def removed[A]: A =
    _removed.asInstanceOf[A]

  @inline
  private final def isRemoved[A](a: A): Boolean =
    equ(a, _removed)
}
