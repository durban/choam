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

import core.Rxn
import internal.mcas.{ Mcas, RefIdGen }
import MsQueue._

/**
 * Unbounded MPMC queue, based on the lock-free Michael-Scott queue described in
 * https://web.archive.org/web/20220123224641/https://www.cs.rochester.edu/~scott/papers/1996_PODC_queues.pdf.
 *
 * Some optimizations are based on the public domain JSR-166 ConcurrentLinkedQueue
 * (https://web.archive.org/web/20220129102848/http://gee.cs.oswego.edu/dl/concurrency-interest/index.html),
 * or on the implementation in the Reagents paper
 * (https://web.archive.org/web/20220214132428/https://www.ccis.northeastern.edu/home/turon/reagents.pdf).
 */
private final class MsQueue[A] private[this] (
  sentinel: Node[A],
  padded: Boolean,
  initRig: RefIdGen,
) extends Queue.UnsealedQueue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)
  private[this] val tail: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)

  private def this(padded: Boolean, initRig: RefIdGen) =
    this(Node(nullOf[A], if (padded) Ref.unsafePadded(End[A](), initRig) else Ref.unsafeUnpadded(End[A](), initRig)), padded = padded, initRig = initRig)

  override val tryDeque: Axn[Option[A]] = {
    head.modifyWith { node =>
      node.next.unsafeTicketRead.flatMapF { ticket =>
        ticket.unsafePeek match {
          case n @ Node(a, _) =>
            // No need to validate `node.next` here, since
            // it is not the last node (thus it won't change).
            Rxn.postCommit(
              // This is to help the GC; a link from old
              // nodes (e.g., the one we're removing now)
              // to newer nodes (e.g., the new head) can
              // put pressure on the GC. So we set the next
              // pointer to `null` after we're done. (This is
              // the same optimization as in ConcurrentLinkedQueue,
              // except we can use `null` instead of a self-link,
              // because we have a sentinel for the `End`.)
              node.next.update { ov =>
                if (ov eq n) null
                else ov
              }
            ).as((n.copy(data = nullOf[A]), Some(a)))
          case End() =>
            ticket.unsafeValidate.as((node, None))
        }
      }
    }
  }

  override val enqueue: Rxn[A, Unit] = Rxn.computed { (a: A) =>
    Rxn.unsafe.suspendContext { ctx =>
      findAndEnqueue(newNode(a, ctx))
    }
  }

  final override def tryEnqueue: Rxn[A, Boolean] =
    this.enqueue.as(true)

  private[this] def newNode(a: A, ctx: Mcas.ThreadContext): Node[A] = {
    val newRef: Ref[Elem[A]] = if (this.padded) {
      Ref.unsafePadded(End[A](), ctx.refIdGen)
    } else {
      Ref.unsafeUnpadded(End[A](), ctx.refIdGen)
    }
    Node(a, newRef)
  }

  private[this] def findAndEnqueue(node: Node[A]): Axn[Unit] = {
    def go(n: Node[A]): Axn[Unit] = {
      n.next.unsafeTicketRead.flatMapF { ticket =>
        ticket.unsafePeek match {
          // TODO: if we allow the tail to lag:
          // case null =>
          //   head.get.flatMapF { h => go(h) }
          case End() =>
            // found true tail; will update, and adjust the tail ref:
            // TODO: we could allow tail to lag by a constant
            ticket.unsafeSet(node) >>> tail.set1(node)
          case nv @ Node(_, _) =>
            // not the true tail, continue;
            // no need to validate `n.next`
            // (it is not the last node, thus it won't change)
            go(n = nv)
        }
      }
    }
    tail.get.flatMapF(go)
  }

  /** For testing */
  private[data] def tailLag: Axn[Int] = {
    def go(n: Node[A], acc: Int): Axn[Int] = {
      n.next.unsafeTicketRead.flatMapF { ticket =>
        ticket.unsafePeek match {
          case null =>
            Rxn.pure(-1)
          case End() =>
            Rxn.pure(acc)
          case nv @ Node(_, _) =>
            go(n = nv, acc = acc + 1)
        }
      }
    }
    tail.get.flatMapF { t => go(t, 0) }
  }
}

private object MsQueue {

  private sealed trait Elem[A]

  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]

  private final case class End[A]() extends Elem[A]

  private final object End {

    private[this] final val _end: End[Any] =
      new End[Any]()

    final def apply[A](): End[A] =
      _end.asInstanceOf[End[A]]
  }

  def apply[A]: Axn[MsQueue[A]] =
    padded[A]

  def padded[A]: Axn[MsQueue[A]] =
    applyInternal(padded = true)

  def unpadded[A]: Axn[MsQueue[A]] =
    applyInternal(padded = false)

  private[data] def fromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[MsQueue[A]] = {
    Queue.fromList[F, MsQueue, A](this.apply[A])(as)
  }

  private[this] def applyInternal[A](padded: Boolean): Axn[MsQueue[A]] =
    Axn.unsafe.delayContext { ctx => new MsQueue(padded = padded, initRig = ctx.refIdGen) }
}
