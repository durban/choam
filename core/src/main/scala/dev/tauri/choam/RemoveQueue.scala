/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import kcas._

import RemoveQueue._

/**
 * Like `MichaelScottQueue`, but also has support
 * for interior node deletion (`remove`; based on
 * `java.util.concurrent.ConcurrentLinkedQueue`).
 */
private[choam] final class RemoveQueue[A] private[this] (sentinel: Node[A], els: Iterable[A])
  extends Queue.WithRemove[A] {

  private[this] val head: Ref[Node[A]] = Ref.mk(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.mk(sentinel)

  def this(els: Iterable[A]) =
    this(Node(nullOf[Ref[A]], Ref.mk(End[A]())), els)

  def this() =
    this(Iterable.empty)

  override val tryDeque: React[Unit, Option[A]] = {
    for {
      node <- head.invisibleRead
      an <- skipTombs(from = node.next)
      res <- an match {
        case None =>
          // empty queue:
          head.cas(node, node) >>> React.ret(None)
        case Some((a, n)) =>
          // deque first node (and drop tombs before it):
          head.cas(node, n.copy(data = nullOf[Ref[A]])) >>> React.ret(Some(a))
      }
    } yield res
  }

  private[this] def skipTombs(from: Ref[Elem[A]]): React[Unit, Option[(A, Node[A])]] = {
    from.invisibleRead.flatMapU {
      case n @ Node(dataRef, nextRef) =>
        dataRef.invisibleRead.flatMapU { a =>
          if (isNull(a)) {
            // found a tombstone (no need to validate, since once
            // it's tombed, it will never be resurrected)
            skipTombs(nextRef)
          } else {
            // CAS data, to make sure it is not tombed concurrently:
            dataRef.cas(a, a).map(_ => Some((a, n)))
          }
        }
      case e @ End() =>
        from.cas(e, e) >>> React.ret(None)
    }
  }

  override val enqueue: React[A, Unit] = React.computed { (a: A) =>
    Ref[Elem[A]](End[A]()).flatMap { nextRef =>
      Ref(a).flatMap { dataRef =>
        findAndEnqueue(Node(dataRef, nextRef))
      }
    }
  }

  private[this] def findAndEnqueue(node: Node[A]): React[Any, Unit] = {
    React.unsafe.delayComputed(tail.invisibleRead.flatMap { (n: Node[A]) =>
      n.next.invisibleRead.flatMap {
        case e @ End() =>
          // found true tail; will CAS, and try to adjust the tail ref:
          React.ret(n.next.cas(e, node).postCommit(tail.cas(n, node).?.void))
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and will retry:
          tail.cas(n, nv).?.map(_ => React.unsafe.retry)
      }
    })
  }

  /**
   * Removes a single instance of the input
   *
   * Note: an item is only removed if it is identical to
   * (i.e., the same object as) the input. That is, items
   * are compared by reference equality.
   */
  override val remove: React[A, Boolean] = React.computed { (a: A) =>
    head.invisibleRead.flatMapU { h =>
      findAndTomb(a, h.next).flatMapU { wasRemoved =>
        if (wasRemoved) {
          // validate head (in case it was dequed concurrently):
          head.cas(h, h).map { _ => true }
        } else {
          React.ret(false)
        }
      }
    }
  }

  private[this] def findAndTomb(item: A, from: Ref[Elem[A]]): React[Unit, Boolean] = {
    from.invisibleRead.flatMap {
      case Node(dataRef, nextRef) =>
        dataRef.invisibleRead.flatMap { a =>
          if (equ(a, item)) {
            // found it
            dataRef.cas(a, nullOf[A]).map(_ => true)
          } else {
            // continue search:
            findAndTomb(item, nextRef)
          }
        }
      case e @ End() =>
        from.cas(e, e) >>> React.ret(false)
    }
  }

  private[choam] override def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]] = {

    def go(e: Elem[A], acc: List[A]): F[List[A]] = e match {
      case Node(null, next) =>
        // sentinel
        F.monad.flatMap(F.run(next.invisibleRead, ())) { go(_, acc) }
      case Node(a, next) =>
        F.monad.flatMap(F.run(next.invisibleRead, ())) { e =>
          F.monad.flatMap(F.run(a.invisibleRead, ())) { a =>
            if (isNull(a)) go(e, acc) // tombstone
            else go(e, a :: acc)
          }
        }
      case End() =>
        F.monad.pure(acc)
    }

    F.monad.map(F.monad.flatMap(F.run(head.invisibleRead, ())) { go(_, Nil) }) { _.reverse }
  }

  els.foreach { a =>
    enqueue.unsafePerform(a, KCAS.NaiveKCAS)
  }
}

private[choam] object RemoveQueue {

  private sealed trait Elem[A]

  /**
   * Sentinel node (head and tail): `data` is `null` (not a `Ref`).
   * Deleted (tombstone) node: `data` is a `Ref` which contains `null`.
   */
  private final case class Node[A](data: Ref[A], next: Ref[Elem[A]]) extends Elem[A]

  private final case class End[A]() extends Elem[A]

  def apply[A]: Action[RemoveQueue[A]] =
    Action.delay { _ => new RemoveQueue }

  def fromList[A](as: List[A]): Action[RemoveQueue[A]] =
    Action.delay { _ => new RemoveQueue(as) }
}
