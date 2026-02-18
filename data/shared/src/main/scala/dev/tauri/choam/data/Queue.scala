/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.{ Monad, Functor, Contravariant, Invariant }
import cats.syntax.all._

import core.{ Rxn, Reactive }

sealed trait Queue[A]
  extends Queue.SourceSink[A]
  with Queue.Add[A]

/**
 * Various queues
 *
 * The operations that may fail (e.g., trying to remove
 * an element from an empty queue) return either a `Boolean`
 * or an `Option` (e.g., removing from an empty queue returns
 * `None`).
 *
 * Method summary of the various operations:
 *
 * |         | `Rxn` (may fail)    | `Rxn` (succeeds) |
 * |---------|---------------------|------------------|
 * | insert  | `Queue.Offer#offer` | `Queue.Add#add`  |
 * | remove  | `Queue.Poll#poll`   | -                |
 * | examine | `Queue.Poll#peek`   | -                |
 *
 * @see [[dev.tauri.choam.async.AsyncQueue$ AsyncQueue]]
 *      for asynchronous (possibly fiber-blocking)
 *      variants of these methods
 */
object Queue {

  sealed trait Poll[+A] {
    def poll: Rxn[Option[A]]
    def peek: Rxn[Option[A]]
  }

  final object Poll {

    implicit final def functorForDevTauriChoamDataQueuePoll: Functor[Queue.Poll] =
      _functorInstance

    private[this] val _functorInstance: Functor[Poll] = new Functor[Poll] {
      final override def map[A, B](fa: Poll[A])(f: A => B): Poll[B] = new Poll[B] {
        final override def poll: Rxn[Option[B]] = fa.poll.map(_.map(f))
        final override def peek: Rxn[Option[B]] = fa.peek.map(_.map(f))
      }
    }
  }

  sealed trait Offer[-A] {
    def offer(a: A): Rxn[Boolean]
  }

  final object Offer {

    implicit final def contravariantFunctorForDevTauriChoamDataQueueOffer: Contravariant[Queue.Offer] =
      _contravariantFunctorInstance

    private[this] val _contravariantFunctorInstance: Contravariant[Queue.Offer] = new Contravariant[Queue.Offer] {
      final override def contramap[A, B](fa: Offer[A])(f: B => A): Offer[B] = new Offer[B] {
        final override def offer(b: B): Rxn[Boolean] = fa.offer(f(b))
      }
    }
  }

  sealed trait Add[-A] extends Queue.Offer[A] {
    def add(a: A): Rxn[Unit]
  }

  final object Add {

    implicit final def contravariantFunctorForDevTauriChoamDataQueueAdd: Contravariant[Queue.Add] =
      _contravariantFunctorInstance

    private[this] val _contravariantFunctorInstance: Contravariant[Queue.Add] = new Contravariant[Queue.Add] {
      final override def contramap[A, B](fa: Add[A])(f: B => A): Add[B] = new Add[B] {
        final override def offer(b: B): Rxn[Boolean] = fa.offer(f(b))
        final override def add(b: B): Rxn[Unit] = fa.add(f(b))
      }
    }
  }

  sealed trait SourceSink[A]
    extends Queue.Poll[A]
    with  Queue.Offer[A]

  final object SourceSink {

    implicit final def invariantFunctorForDevTauriChoamDataQueueSourceSink: Invariant[Queue.SourceSink] =
      _invariantFunctorInstance

    private[this] val _invariantFunctorInstance: Invariant[SourceSink] = new Invariant[SourceSink] {
      final override def imap[A, B](fa: SourceSink[A])(f: A => B)(g: B => A): SourceSink[B] = new SourceSink[B] {
        final override def poll: Rxn[Option[B]] = fa.poll.map(_.map(f))
        final override def peek: Rxn[Option[B]] = fa.peek.map(_.map(f))
        final override def offer(b: B): Rxn[Boolean] = fa.offer(g(b))
      }
    }
  }

  sealed trait WithSize[A] extends Queue[A] {
    def size: Rxn[Int]
  }

  final object WithSize {

    implicit final def invariantFunctorForDevTauriChoamDataQueueWithSize: Invariant[Queue.WithSize] =
      _invariantFunctorInstance

    private[this] val _invariantFunctorInstance: Invariant[WithSize] = new Invariant[WithSize] {
      final override def imap[A, B](fa: WithSize[A])(f: A => B)(g: B => A): WithSize[B] = new WithSize[B] {
        final override def poll: Rxn[Option[B]] = fa.poll.map(_.map(f))
        final override def peek: Rxn[Option[B]] = fa.peek.map(_.map(f))
        final override def offer(b: B): Rxn[Boolean] = fa.offer(g(b))
        final override def add(b: B): Rxn[Unit] = fa.add(g(b))
        final override def size: Rxn[Int] = fa.size
      }
    }
  }

  implicit final def invariantFunctorForDevTauriChoamDataQueue: Invariant[Queue] =
    _invariantFunctorInstance

  private[this] val _invariantFunctorInstance: Invariant[Queue] = new Invariant[Queue] {
    final override def imap[A, B](fa: Queue[A])(f: A => B)(g: B => A): Queue[B] = new Queue[B] {
      final override def poll: Rxn[Option[B]] = fa.poll.map(_.map(f))
      final override def peek: Rxn[Option[B]] = fa.peek.map(_.map(f))
      final override def offer(b: B): Rxn[Boolean] = fa.offer(g(b))
      final override def add(b: B): Rxn[Unit] = fa.add(g(b))
    }
  }

  final def unbounded[A]: Rxn[Queue[A]] =
    unbounded(AllocationStrategy.Default)

  final def bounded[A](bound: Int): Rxn[Queue.SourceSink[A]] =
    bounded(bound, AllocationStrategy.Default)

  final def bounded[A](bound: Int, str: AllocationStrategy): Rxn[Queue.SourceSink[A]] =
    dropping(bound, str)

  final def dropping[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    dropping(capacity, AllocationStrategy.Default)

  final def dropping[A](capacity: Int, str: AllocationStrategy): Rxn[Queue.WithSize[A]] =
    DroppingQueue.apply[A](capacity, str)

  final def ringBuffer[A](capacity: Int): Rxn[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity)

  final def ringBuffer[A](capacity: Int, str: AllocationStrategy): Rxn[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity, str)

  final def unboundedWithSize[A]: Rxn[Queue.WithSize[A]] =
    MsQueue.withSize[A]

  // TODO: boundedWithSize : Queue.SourceSinkWithSize (?)

  private[choam] final def unbounded[A](str: AllocationStrategy): Rxn[Queue[A]] =
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

  private[data] final def fromList[F[_] : Reactive, Q[a] <: Queue[a], A](mkEmpty: Rxn[Q[A]])(as: List[A]): F[Q[A]] = {
    implicit val m: Monad[F] = Reactive[F].monad
    mkEmpty.run[F].flatMap { q =>
      as.traverse(a => q.add(a).run[F]).as(q)
    }
  }

  private[choam] final def msQueueFromList[F[_] : Reactive, A](
    as: List[A],
    str: AllocationStrategy = AllocationStrategy.Default,
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
