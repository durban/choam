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

import cats.Monad
import cats.syntax.all._

import core.{ Rxn, Ref, Reactive }

sealed trait QueueSource[+A] {

  def poll: Rxn[Option[A]]

  private[choam] final def drainOnce[F[_], AA >: A](implicit F: Reactive[F]): F[List[AA]] = {
    F.monad.tailRecM(List.empty[AA]) { acc =>
      F.monad.map(F.run(this.poll)) {
        case Some(a) => Left(a :: acc)
        case None => Right(acc.reverse)
      }
    }
  }
}

sealed trait QueueSink[-A] {
  def offer(a: A): Rxn[Boolean]
}

sealed trait QueueSourceSink[A]
  extends QueueSource[A]
  with  QueueSink[A]

sealed trait UnboundedQueueSink[-A] extends QueueSink[A] {
  def add(a: A): Rxn[Unit]
}

sealed trait Queue[A]
  extends QueueSourceSink[A]
  with UnboundedQueueSink[A]

/**
 * Various queues
 *
 * The operations that may fail (e.g., trying to remove
 * an element form an empty queue) return either a `Boolean`
 * or an `Option` (e.g., removing from an empty queue returns
 * `None`).
 *
 * Method summary of the various operations:
 *
 * |         | `Rxn` (may fail)   | `Rxn` (succeeds)         |
 * |---------|--------------------|--------------------------|
 * | insert  | `QueueSink#offer`  | `UnboundedQueueSink#add` |
 * | remove  | `QueueSource#poll` | `remove`                 |
 * | examine | `peek`             | -                        |
 *
 * @see [[dev.tauri.choam.async.AsyncQueue]]
 *      for asynchronous (possibly fiber-blocking)
 *      variants of these methods
 */
object Queue {

  private[choam] trait UnsealedQueueSource[+A]
    extends QueueSource[A]

  private[choam] trait UnsealedQueueSink[-A]
    extends QueueSink[A]

  private[choam] trait UnsealedQueueSourceSink[A]
    extends QueueSourceSink[A]

  private[choam] trait UnsealedQueue[A]
    extends Queue[A]

  sealed trait WithSize[A] extends Queue[A] {
    def size: Rxn[Int]
  }

  private[choam] trait UnsealedWithSize[A]
    extends WithSize[A]

  final def unbounded[A]: Rxn[Queue[A]] =
    unbounded(Ref.AllocationStrategy.Default)

  final def unbounded[A](str: Ref.AllocationStrategy): Rxn[Queue[A]] =
    MsQueue[A](str)

  final def bounded[A](bound: Int): Rxn[QueueSourceSink[A]] =
    dropping(bound)

  final def dropping[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    DroppingQueue.apply[A](capacity)

  final def ringBuffer[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity)

  // TODO: do we need this?
  private[choam] def lazyRingBuffer[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    RingBuffer.lazyRingBuffer[A](capacity)

  final def unboundedWithSize[A]: Rxn[Queue.WithSize[A]] = {
    Queue.unbounded[A].flatMap { q =>
      Ref.unpadded[Int](0).map { s =>
        new WithSize[A] {

          final override def poll: Rxn[Option[A]] = {
            q.poll.flatMap {
              case r @ Some(_) => s.update(_ - 1).as(r)
              case None => Rxn.pure(None)
            }
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

  private[data] final def fromList[F[_] : Reactive, Q[a] <: Queue[a], A](mkEmpty: Rxn[Q[A]])(as: List[A]): F[Q[A]] = {
    implicit val m: Monad[F] = Reactive[F].monad
    mkEmpty.run[F].flatMap { q =>
      as.traverse(a => q.add(a).run[F]).as(q)
    }
  }
}
