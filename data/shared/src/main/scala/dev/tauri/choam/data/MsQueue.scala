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

import core.{ Rxn, Ref, Reactive }
import internal.mcas.{ Mcas, RefIdGen }
import MsQueue._

/**
 * Unbounded MPMC queue, based on the lock-free Michael-Scott queue described in
 * "Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue Algorithms"
 * (https://www.cs.rochester.edu/~scott/papers/1996_PODC_queues.pdf).
 *
 * Some optimizations are based on the public domain JSR-166 ConcurrentLinkedQueue
 * (http://gee.cs.oswego.edu/dl/concurrency-interest/index.html),
 * or on the implementation in the Reagents paper
 * (https://www.ccs.neu.edu/home/turon/reagents.pdf).
 */
private final class MsQueue[A] private[this] (
  sentinel: Node[A],
  str: AllocationStrategy,
  initRig: RefIdGen,
) extends Queue.UnsealedQueue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafe(sentinel, str.withPadded(true), initRig)
  private[this] val tail: Ref[Node[A]] = Ref.unsafe(sentinel, str.withPadded(true), initRig)

  private def this(str: AllocationStrategy, initRig: RefIdGen) =
    this(Node(nullOf[A], Ref.unsafe(End[A](), str, initRig)), str = str, initRig = initRig)

  final override val poll: Rxn[Option[A]] = {
    head.get.flatMap { node =>
      Rxn.unsafe.ticketRead(node.next).flatMap { ticket =>
        ticket.unsafePeek match {
          case n @ Node(a, _) =>
            // No need to validate `node.next` here, since
            // it is not the last node (thus it won't change).
            head.set(n.copy(data = nullOf[A])).as(Some(a)).postCommit(
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
            )
          case End() =>
            ticket.unsafeValidate.as(None)
        }
      }
    }
  }

  final override def peek: Rxn[Option[A]] = {
    head.get.flatMap { node =>
      Rxn.unsafe.ticketRead(node.next).flatMap { ticket =>
        ticket.unsafePeek match {
          case Node(a, _) =>
            // see comment in `poll` above
            Rxn.pure(Some(a))
          case End() =>
            ticket.unsafeValidate.as(None)
        }
      }
    }
  }

  final override def add(a: A): Rxn[Unit] = Rxn.unsafe.suspendContext { ctx =>
    findAndEnqueue(newNode(a, ctx))
  }

  final override def offer(a: A): Rxn[Boolean] =
    this.add(a).as(true)

  private[this] def newNode(a: A, ctx: Mcas.ThreadContext): Node[A] = {
    val newRef: Ref[Elem[A]] = Ref.unsafe(End[A](), this.str, ctx.refIdGen)
    Node(a, newRef)
  }

  private[this] def findAndEnqueue(node: Node[A]): Rxn[Unit] = {
    def go(n: Node[A]): Rxn[Unit] = {
      Rxn.unsafe.ticketRead(n.next).flatMap { ticket =>
        ticket.unsafePeek match {
          // TODO: if we allow the tail to lag:
          // case null =>
          //   head.get.flatMap { h => go(h) }
          case End() =>
            // found true tail; will update, and adjust the tail ref:
            // TODO: we could allow tail to lag by a constant
            ticket.unsafeSet(node) *> tail.set(node)
          case nv @ Node(_, _) =>
            // not the true tail, continue;
            // no need to validate `n.next`
            // (it is not the last node, thus it won't change)
            go(n = nv)
        }
      }
    }
    tail.get.flatMap(go)
  }

  /** For testing */
  private[data] def tailLag: Rxn[Int] = {
    def go(n: Node[A], acc: Int): Rxn[Int] = {
      Rxn.unsafe.ticketRead(n.next).flatMap { ticket =>
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
    tail.get.flatMap { t => go(t, 0) }
  }
}

private object MsQueue {

  final def apply[A]: Rxn[MsQueue[A]] =
    apply[A](AllocationStrategy.Default)

  final def apply[A](str: AllocationStrategy): Rxn[MsQueue[A]] = {
    Rxn.unsafe.delayContext { ctx => new MsQueue(str = str, initRig = ctx.refIdGen) }
  }

  final def withSize[A]: Rxn[Queue.WithSize[A]] = {
    apply[A].flatMap { q =>
      Rxn.unsafe.delayContext { ctx =>
        new Queue.UnsealedQueueWithSize[A] {

          private[this] val s =
            Ref.unsafe[Int](0, AllocationStrategy.Default.withPadded(true), ctx.refIdGen)

          final override def poll: Rxn[Option[A]] = {
            q.poll.flatMap {
              case r @ Some(_) => s.update(_ - 1).as(r)
              case None => Rxn.none
            }
          }

          final override def peek: Rxn[Option[A]] = {
            q.peek
          }

          final override def add(a: A): Rxn[Unit] =
            s.update(_ + 1) *> q.add(a)

          final override def offer(a: A): Rxn[Boolean] =
            this.add(a).as(true)

          final override def size: Rxn[Int] =
            s.get
        }
      }
    }
  }

  private[data] def fromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[MsQueue[A]] = {
    Queue.fromList[F, MsQueue, A](this.apply[A])(as)
  }

  private sealed trait Elem[A]

  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]

  private final case class End[A]() extends Elem[A]

  private final object End {

    private[this] final val _end: End[Any] =
      new End[Any]()

    final def apply[A](): End[A] =
      _end.asInstanceOf[End[A]]
  }
}
