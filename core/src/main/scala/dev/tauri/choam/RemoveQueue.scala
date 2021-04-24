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

  private[this] val head: Ref[Node[A]] = Ref.unsafe(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.unsafe(sentinel)

  def this(els: Iterable[A]) =
    this(Node(nullOf[Ref[A]], Ref.unsafe(End[A]())), els)

  def this() =
    this(Iterable.empty)

  override val tryDeque: Axn[Option[A]] = {
    for {
      node <- head.unsafeInvisibleRead
      an <- skipTombs(from = node.next)
      res <- an match {
        case None =>
          // empty queue:
          head.unsafeCas(node, node) >>> Rxn.ret(None)
        case Some((a, n)) =>
          // deque first node (and drop tombs before it):
          head.unsafeCas(node, n.copy(data = nullOf[Ref[A]])) >>> Rxn.ret(Some(a))
      }
    } yield res
  }

  private[this] def skipTombs(from: Ref[Elem[A]]): Axn[Option[(A, Node[A])]] = {
    from.unsafeInvisibleRead.flatMapU {
      case n @ Node(dataRef, nextRef) =>
        dataRef.unsafeInvisibleRead.flatMapU { a =>
          if (isNull(a)) {
            // found a tombstone (no need to validate, since once
            // it's tombed, it will never be resurrected)
            skipTombs(nextRef)
          } else {
            // CAS data, to make sure it is not tombed concurrently:
            dataRef.unsafeCas(a, a).map(_ => Some((a, n)))
          }
        }
      case e @ End() =>
        from.unsafeCas(e, e) >>> Rxn.ret(None)
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
    Rxn.unsafe.delayComputed(tail.unsafeInvisibleRead.flatMap { (n: Node[A]) =>
      n.next.unsafeInvisibleRead.flatMap {
        case e @ End() =>
          // found true tail; will CAS, and try to adjust the tail ref:
          Rxn.ret(n.next.unsafeCas(e, node).postCommit(tail.unsafeCas(n, node).?.void))
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and will retry:
          tail.unsafeCas(n, nv).?.map(_ => Rxn.unsafe.retry)
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
  override val remove: Rxn[A, Boolean] = Rxn.computed { (a: A) =>
    head.unsafeInvisibleRead.flatMapU { h =>
      findAndTomb(a, h.next).flatMapU { wasRemoved =>
        if (wasRemoved) {
          // validate head (in case it was dequed concurrently):
          head.unsafeCas(h, h).map { _ => true }
        } else {
          Rxn.ret(false)
        }
      }
    }
  }

  private[this] def findAndTomb(item: A, from: Ref[Elem[A]]): Axn[Boolean] = {
    from.unsafeInvisibleRead.flatMap {
      case Node(dataRef, nextRef) =>
        dataRef.unsafeInvisibleRead.flatMap { a =>
          if (equ(a, item)) {
            // found it
            dataRef.unsafeCas(a, nullOf[A]).map(_ => true)
          } else {
            // continue search:
            findAndTomb(item, nextRef)
          }
        }
      case e @ End() =>
        from.unsafeCas(e, e) >>> Rxn.ret(false)
    }
  }

  private[choam] override def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]] = {

    def go(e: Elem[A], acc: List[A]): F[List[A]] = e match {
      case Node(null, next) =>
        // sentinel
        F.monad.flatMap(F.run(next.unsafeInvisibleRead, ())) { go(_, acc) }
      case Node(a, next) =>
        F.monad.flatMap(F.run(next.unsafeInvisibleRead, ())) { e =>
          F.monad.flatMap(F.run(a.unsafeInvisibleRead, ())) { a =>
            if (isNull(a)) go(e, acc) // tombstone
            else go(e, a :: acc)
          }
        }
      case End() =>
        F.monad.pure(acc)
    }

    F.monad.map(F.monad.flatMap(F.run(head.unsafeInvisibleRead, ())) { go(_, Nil) }) { _.reverse }
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

  def apply[A]: Axn[RemoveQueue[A]] =
    Axn.delay { _ => new RemoveQueue }

  def fromList[A](as: List[A]): Axn[RemoveQueue[A]] =
    Axn.delay { _ => new RemoveQueue(as) }
}
