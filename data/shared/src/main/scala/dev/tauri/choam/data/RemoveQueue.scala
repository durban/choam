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

import core.{ Rxn, Axn, Ref }
import internal.mcas.RefIdGen
import RemoveQueue.{ Elem, Node, End, dequeued, isDequeued, isRemoved }

/**
 * Internal API, for (Gen)WaitList to use.
 *
 * Like `MsQueue`, but also has support for interior node deletion
 * (`remove`), based on the public domain JSR-166 ConcurrentLinkedQueue
 * (https://web.archive.org/web/20220129102848/http://gee.cs.oswego.edu/dl/concurrency-interest/index.html).
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

  override val tryDeque: Axn[Option[A]] = {
    head.modifyWith { node =>
      skipRemoved(from = node.next).flatMap {
        case None =>
          // empty queue:
          Rxn.ret((node, None))
        case Some((a, n)) =>
          // deque first node (and drop tombs before it):
          Rxn.ret((n.copy(data = nullOf[Ref[A]]), Some(a)))
      }
    }
  }

  private[this] def skipRemoved(from: Ref[Elem[A]]): Axn[Option[(A, Node[A])]] = {
    from.get.flatMapF {
      case n @ Node(dataRef, nextRef) =>
        dataRef.get.flatMapF { a =>
          if (isRemoved(a)) {
            skipRemoved(nextRef)
          } else if (isDequeued(a)) {
            impossible("tryDeque found an already dequeued node")
          } else {
            dataRef.set1(dequeued[A]).as(Some((a, n)))
          }
        }
      case End() =>
        Rxn.pure(None)
    }
  }

  final override def tryEnqueue: Rxn[A, Boolean] =
    this.enqueue.as(true)

  override def enqueue: Rxn[A, Unit] = Rxn.computed { (a: A) =>
    Ref.unpadded[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref.unpadded(a).flatMap { dataRef =>
        findAndEnqueue(Node(dataRef, nextRef))
      }
    }
  }

  val enqueueWithRemover: Rxn[A, Axn[Boolean]] = Rxn.computed { (a: A) =>
    Ref.unpadded[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref.unpadded(a).flatMap { dataRef =>
        val newNode = Node(dataRef, nextRef)
        findAndEnqueue(newNode).as(newNode.remover)
      }
    }
  }

  // TODO: we could allow tail to lag by a constant
  private[this] def findAndEnqueue(node: Node[A]): Axn[Unit] = {
    def go(n: Node[A]): Axn[Unit] = {
      n.next.get.flatMapF {
        case End() =>
          // found true tail; will update, and adjust the tail ref:
          n.next.set1(node) >>> tail.set1(node)
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and continue:
          go(n = nv)
      }
    }
    tail.get.flatMapF(go)
  }
}

private[choam] object RemoveQueue {

  def apply[A]: Axn[RemoveQueue[A]] =
    Axn.unsafe.delayContext { ctx => new RemoveQueue(ctx.refIdGen) }

  private sealed trait Elem[A]

  /**
   * Sentinel node (head and tail): `data` is `null` (not a `Ref`).
   * Deleted (tombstone) node: `data` is a `Ref` which contains `Tombstone`.
   */
  private final case class Node[A](data: Ref[A], next: Ref[Elem[A]])
    extends Elem[A] {

    final def remover: Axn[Boolean] = {
      this.data.upd[Any, Boolean] { (ov, _) =>
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
