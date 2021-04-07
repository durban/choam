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

import MichaelScottQueue._

private[choam] final class MichaelScottQueue[A] private[this] (sentinel: Node[A], els: Iterable[A])
  extends Queue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafe(sentinel)
  private[this] val tail: Ref[Node[A]] = Ref.unsafe(sentinel)

  private def this(els: Iterable[A]) =
    this(Node(nullOf[A], Ref.unsafe(End[A]())), els)

  private def this() =
    this(Iterable.empty)

  override val tryDeque: Axn[Option[A]] = {
    for {
      node <- head.unsafeInvisibleRead
      next <- node.next.unsafeInvisibleRead
      res <- next match {
        case n @ Node(a, _) =>
          // No need to also validate `node.next`, since
          // it is not the last node (thus it won't change).
          head.unsafeCas(node, n.copy(data = nullOf[A])) >>> React.ret(Some(a))
        case End() =>
          head.unsafeCas(node, node) >>> node.next.unsafeCas(next, next) >>> React.ret(None)
      }
    } yield res
  }

  override val enqueue: React[A, Unit] = React.computed { (a: A) =>
    findAndEnqueue(Node(a, Ref.unsafe(End[A]())))
  }

  private[this] def findAndEnqueue(node: Node[A]): React[Any, Unit] = {
    React.unsafe.delayComputed(tail.unsafeInvisibleRead.flatMap { (n: Node[A]) =>
      n.next.unsafeInvisibleRead.flatMap {
        case e @ End() =>
          // found true tail; will CAS, and try to adjust the tail ref:
          React.ret(n.next.unsafeCas(e, node).postCommit(tail.unsafeCas(n, node).?.void))
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and will retry:
          tail.unsafeCas(n, nv).?.map(_ => React.unsafe.retry)
      }
    })
  }

  private[choam] override def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]] = {

    def go(e: Elem[A], acc: List[A]): F[List[A]] = e match {
      case Node(null, next) =>
        // sentinel
        F.monad.flatMap(F.run(next.unsafeInvisibleRead, ())) { go(_, acc) }
      case Node(a, next) =>
        F.monad.flatMap(F.run(next.unsafeInvisibleRead, ())) { go(_, a :: acc) }
      case End() =>
        F.monad.pure(acc)
    }

    F.monad.map(F.monad.flatMap(F.run(head.unsafeInvisibleRead, ())) { go(_, Nil) }) { _.reverse }
  }

  els.foreach { a =>
    enqueue.unsafePerform(a, KCAS.NaiveKCAS)
  }
}

private[choam] object MichaelScottQueue {

  private sealed trait Elem[A]
  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]
  private final case class End[A]() extends Elem[A]

  def apply[A]: Axn[MichaelScottQueue[A]] =
    Axn.delay { _ => new MichaelScottQueue }

  def fromList[A](as: List[A]): Axn[MichaelScottQueue[A]] =
    Axn.delay { _ => new MichaelScottQueue(as) }
}
