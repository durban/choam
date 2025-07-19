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

sealed trait Queue[A]
  extends Queue.SourceSink[A]
  with Queue.Add[A]

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
 * |         | `Rxn` (may fail)    | `Rxn` (succeeds) |
 * |---------|---------------------|------------------|
 * | insert  | `Queue.Offer#offer` | `Queue.Add#add`  |
 * | remove  | `Queue.Poll#poll`   | -                |
 * | examine | `peek`              | -                |
 *
 * TODO: implement `peek`
 *
 * @see [[dev.tauri.choam.async.AsyncQueue$ AsyncQueue]]
 *      for asynchronous (possibly fiber-blocking)
 *      variants of these methods
 */
object Queue {

  sealed trait Poll[+A] {
    def poll: Rxn[Option[A]]
  }

  sealed trait Offer[-A] {
    def offer(a: A): Rxn[Boolean]
  }

  sealed trait Add[-A] extends Queue.Offer[A] {
    def add(a: A): Rxn[Unit]
  }

  sealed trait SourceSink[A]
    extends Queue.Poll[A]
    with  Queue.Offer[A]

  sealed trait WithSize[A] extends Queue[A] {
    def size: Rxn[Int]
  }

  final def unbounded[A]: Rxn[Queue[A]] =
    unbounded(Ref.AllocationStrategy.Default)

  final def bounded[A](bound: Int): Rxn[Queue.SourceSink[A]] =
    dropping(bound)

  final def dropping[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    DroppingQueue.apply[A](capacity)

  final def ringBuffer[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity)

  final def unboundedWithSize[A]: Rxn[Queue.WithSize[A]] = {
    MsQueue.withSize[A]
  }

  // TODO: boundedWithSize : Queue.SourceSinkWithSize (?)

  private[choam] final def unbounded[A](str: Ref.AllocationStrategy): Rxn[Queue[A]] =
    MsQueue[A](str)

  private[choam] trait UnsealedQueuePoll[+A]
    extends Queue.Poll[A]

  private[choam] trait UnsealedQueueOffer[-A]
    extends Queue.Offer[A]

  private[choam] trait UnsealedQueueSourceSink[A]
    extends Queue.SourceSink[A]

  private[choam] trait UnsealedQueue[A]
    extends Queue[A]

  private[choam] trait UnsealedQueueWithSize[A]
    extends WithSize[A]

  // TODO: do we need this?
  private[choam] def lazyRingBuffer[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    RingBuffer.lazyRingBuffer[A](capacity)

  private[data] final def fromList[F[_] : Reactive, Q[a] <: Queue[a], A](mkEmpty: Rxn[Q[A]])(as: List[A]): F[Q[A]] = {
    implicit val m: Monad[F] = Reactive[F].monad
    mkEmpty.run[F].flatMap { q =>
      as.traverse(a => q.add(a).run[F]).as(q)
    }
  }

  private[choam] final def msQueueFromList[F[_] : Reactive, A](
    as: List[A],
    str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default,
  ): F[Queue[A]] = {
    Reactive[F].monad.widen(fromList(MsQueue[A](str))(as))
  }

  private[choam] final def removeQueueFromList[F[_] : Reactive, A](
    as: List[A],
  ): F[RemoveQueue[A]] = {
    fromList[F, RemoveQueue, A](RemoveQueue.apply[A])(as)
  }

  private[choam] final def gcHostileMsQueueFromList[F[_] : Reactive, A](as: List[A]): F[Queue[A]] = {
    Reactive[F].monad.widen(fromList(GcHostileMsQueue[A])(as))
  }

  private[choam] final def drainOnce[F[_], A](queue: Queue.Poll[A])(implicit F: Reactive[F]): F[List[A]] = {
      F.monad.tailRecM(List.empty[A]) { acc =>
        F.monad.map(F.run(queue.poll)) {
          case Some(a) => Left(a :: acc)
          case None => Right(acc.reverse)
        }
      }
    }

  private[choam] implicit final class DrainOnceSyntax[A](private val self: Queue.Poll[A]) extends AnyVal {
    private[choam] final def drainOnce[F[_]](implicit F: Reactive[F]): F[List[A]] = {
      Queue.drainOnce(self)
    }
  }
}
